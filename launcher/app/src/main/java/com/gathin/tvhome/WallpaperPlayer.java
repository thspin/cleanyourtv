package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays and rotates a theme's local wallpaper images while Home is visible.
 *
 * Two modes (config.json, wallpaper_mode). The default, {@code "rotation"}, picks a new picture every
 * wallpaper_rotation_minutes into one ImageView. {@code "time"} shows one picture per part of the day
 * ({@link TimeWindow}): the four are decoded once into memory, and a change crossfades between two
 * stacked ImageViews, which costs one alpha property per frame.
 */
final class WallpaperPlayer {
    /** How long the change between two parts of the day takes, in milliseconds. */
    static final long CROSSFADE_MS = 2000;
    /** reduced_motion: the once-a-minute check stays, but the change is short enough to stay calm. */
    private static final long CROSSFADE_REDUCED_MS = 400;

    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    /** wallpaper_mode "time": pictures by the clock instead of a rotation. */
    private final boolean byTime;
    private final long crossfade;
    /** Each slot's file, fixed at construction. */
    private final Map<TimeWindow.Slot, File> sources = new EnumMap<>(TimeWindow.Slot.class);
    /** Each slot's packaged drawable id, used when the theme pushed no bg_* file. */
    private final Map<TimeWindow.Slot, Integer> bundled = new EnumMap<>(TimeWindow.Slot.class);
    /** Decoded pictures by slot, owned by the main thread (each decode is handed over by a post). */
    private final Map<TimeWindow.Slot, Bitmap> bitmaps = new EnumMap<>(TimeWindow.Slot.class);
    private final Context context;
    private final Resources resources;
    /** "time": the picture underneath {@link #image}, where the old slot sits during a fade. */
    private ImageView background;
    /** The slot whose picture is on screen, and the one the current fade lands on. */
    private TimeWindow.Slot shown;
    private TimeWindow.Slot target;
    private boolean preloaded;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        this.context = context.getApplicationContext();
        resources = context.getResources();
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = imageView(context);
        byTime = config.wallpaperByTime;
        // animations=false cuts at once, like every other change in the launcher; reduced_motion keeps a
        // fade, but short, so a GPU that stumbles on motion never has to hold a long one.
        if (!config.animations) crossfade = 0L;
        else if (config.reducedMotion) crossfade = CROSSFADE_REDUCED_MS;
        else crossfade = CROSSFADE_MS;
        // A theme that pushed no picture still gets the packaged backdrops in "time" mode, so the gate is
        // "the theme wants a wallpaper", not "the sdcard has files".
        boolean hasBackdrop = !files.isEmpty() || (byTime && packagedAvailable());
        if (config.wallpaperEnabled && hasBackdrop) {
            if (byTime) {
                // The old picture goes in first, so the new one fades in over it. The other order
                // left the outgoing picture on top and the crossfade was a hard cut at the end of it.
                background = imageView(context);
                background.setAlpha(0f);
                root.addView(background, 0, fullScreen());
            }
            root.addView(image, byTime ? 1 : 0, fullScreen());
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, byTime ? 2 : 1, fullScreen());
            }
            if (byTime) showFirstSlot(); else showFirst();
        }
    }

    private static ImageView imageView(Context context) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static FrameLayout.LayoutParams fullScreen() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, random);
        }
    }

    /**
     * "time" mode: maps each slot to its file. A theme without a single bg_* image keeps the old
     * behaviour, rotation or the one wallpaper; otherwise the pictures are decoded in the background
     * from here, so Home only ever swaps in bitmaps that are already in memory.
     */
    private void showFirstSlot() {
        for (TimeWindow.Slot slot : TimeWindow.Slot.values()) {
            File source = find(slot);
            if (source != null) sources.put(slot, source);
        }
        if (sources.isEmpty()) {
            // No theme pushed a bg_* file, so fall back to the four backdrops packaged in the APK: the
            // launcher then follows the clock out of the box, with nothing on the sdcard to lose.
            for (TimeWindow.Slot slot : TimeWindow.Slot.values()) {
                int id = packagedBackdrop(slot);
                if (id != 0) bundled.put(slot, id);
            }
        }
        if (sources.isEmpty() && bundled.isEmpty()) showFirst(); else decodeRemaining();
    }

    /** The drawable name {@link TimeWindow#fileName} gives this slot, as an id; 0 when the build has none. */
    private int packagedBackdrop(TimeWindow.Slot slot) {
        return resources.getIdentifier(TimeWindow.fileName(slot), "drawable", context.getPackageName());
    }

    /** True when this build packages at least one backdrop, so "time" mode has something to show. */
    private boolean packagedAvailable() {
        for (TimeWindow.Slot slot : TimeWindow.Slot.values()) {
            if (packagedBackdrop(slot) != 0) return true;
        }
        return false;
    }

    void start() {
        active = true;
        if (byTime && (!sources.isEmpty() || !bundled.isEmpty())) {
            // Cheap and idempotent: finishes anything the decoder had not reached yet, and picks up the
            // picture of the current part of the day as soon as it is in memory.
            decodeRemaining();
            showSlot();
        } else {
            update();
        }
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
        // A bitmap still set on a view must be dropped by the view before it is recycled: freeing it
        // under the renderer is a native crash. Both views then hold nothing.
        image.setImageDrawable(null);
        if (background != null) background.setImageDrawable(null);
        for (Bitmap bitmap : bitmaps.values()) {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
        bitmaps.clear();
        sources.clear();
        bundled.clear();
    }

    void update() {
        if (!active || closed) return;
        if (byTime && (!sources.isEmpty() || !bundled.isEmpty())) {
            showSlot();
            return;
        }
        if (loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                image.setImageBitmap(bitmap);
                displayed = next.getName();
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * "time" mode, once a minute: nothing at all unless the part of the day changed, and then a crossfade
     * between two full-screen ImageViews. The old picture goes under, the new one fades in above it, so
     * a frame costs one alpha property and no canvas redraw.
     */
    private void showSlot() {
        TimeWindow.Slot now = now();
        if (now == target || background == null) return;
        // A part of the day with no picture of its own keeps the earlier one on screen until the next
        // part that has one, so a theme with only bg_night and bg_dusk still changes twice a day.
        TimeWindow.Slot slot = now;
        Bitmap bitmap = null;
        for (int i = 0; i < TimeWindow.Slot.values().length && bitmap == null; i++) {
            slot = earlier(now, i);
            bitmap = bitmaps.get(slot);
            if (bitmap != null && bitmap.isRecycled()) bitmap = null;
        }
        if (bitmap == null) return;   // nothing decoded yet: start() or the decoder's post will retry
        target = slot;
        TimeWindow.Slot destination = slot;   // what this fade lands on, even if the clock moves on meanwhile
        Bitmap old = shown == null ? null : bitmaps.get(shown);
        if (old == null || old.isRecycled()) {
            background.animate().cancel();
            image.animate().cancel();
            background.setAlpha(0f);
            image.setAlpha(1f);
            image.setImageBitmap(bitmap);
            shown = destination;
            return;
        }
        background.animate().cancel();
        image.animate().cancel();
        background.setImageBitmap(old);   // already decoded for the previous slot: no new memory
        background.setAlpha(1f);
        image.setImageBitmap(bitmap);
        if (crossfade == 0) {
            background.setAlpha(0f);      // animations=false: a straight cut, same end state as the fade
            shown = destination;
            return;
        }
        image.setAlpha(0f);
        // Only the top layer's alpha animates: the lower one stays at full opacity underneath, which is
        // the renderer's cheap "fade a view in over another" path.
        image.animate().alpha(1f).setDuration(crossfade).setInterpolator(null).withEndAction(() -> {
            background.setAlpha(0f);
            if (!closed) shown = destination;
        }).start();
    }

    /**
     * Decodes whatever is not in memory yet, one picture per task on the shared background thread.
     * Bitmaps are created on that thread and handed to the main thread through {@code root.post}, so
     * the map is only ever written where it is read.
     */
    private void decodeRemaining() {
        if (preloaded || closed) return;
        preloaded = true;
        for (TimeWindow.Slot slot : TimeWindow.Slot.values()) {
            if (bitmaps.containsKey(slot)) continue;
            File source = sources.get(slot);
            Integer packaged = bundled.get(slot);
            if (source == null && packaged == null) continue;
            String label = source != null ? source.getName() : "bg_" + slot.name().toLowerCase(Locale.ROOT);
            decoder.execute(() -> {
                Bitmap bitmap = source != null ? decode(source) : decodeDrawable(packaged);
                root.post(() -> {
                    if (closed) {
                        if (bitmap != null) bitmap.recycle();
                        return;
                    }
                    if (bitmap == null) {
                        Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + label);
                        return;
                    }
                    if (bitmaps.containsKey(slot)) {
                        bitmap.recycle();
                        return;
                    }
                    bitmaps.put(slot, bitmap);
                    if (active) showSlot();   // the current part of the day may have just arrived
                });
            });
        }
    }

    /** The file named after {@code slot} ("bg_night.jpg", "bg_morning.png", ...), or null if the theme has none. */
    private File find(TimeWindow.Slot slot) {
        String base = TimeWindow.fileName(slot);
        String[] extensions = TimeWindow.extensions();
        for (String extension : extensions) {
            File exact = WallpaperRotation.find(files, base + "." + extension);
            if (exact != null) return exact;
        }
        for (File file : files) {   // themes are pushed from Windows too: the name may differ in case
            String name = file.getName().toLowerCase(Locale.ROOT);
            for (String extension : extensions) {
                if (name.equals(base + "." + extension)) return file;
            }
        }
        return null;
    }

    /** The slot {@code steps} places before {@code now}, wrapping around midnight. */
    private static TimeWindow.Slot earlier(TimeWindow.Slot now, int steps) {
        TimeWindow.Slot slot = now;
        for (int i = 0; i < steps; i++) slot = previous(slot);
        return slot;
    }

    private static TimeWindow.Slot previous(TimeWindow.Slot slot) {
        switch (slot) {
            case NIGHT:
                return TimeWindow.Slot.DUSK;
            case MORNING:
                return TimeWindow.Slot.NIGHT;
            case AFTERNOON:
                return TimeWindow.Slot.MORNING;
            case DUSK:
            default:
                return TimeWindow.Slot.AFTERNOON;
        }
    }

    private static TimeWindow.Slot now() {
        return TimeWindow.at(TimeWindow.minuteOfDay(System.currentTimeMillis(), TimeZone.getDefault()));
    }

    /** Same pipeline as {@link #decode}, for a picture packaged in the APK instead of a file on the sdcard. */
    private Bitmap decodeDrawable(int id) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(resources, id, bounds);
            int width = bounds.outWidth, height = bounds.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeResource(resources, id, options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
