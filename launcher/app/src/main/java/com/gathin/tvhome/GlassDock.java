package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

/**
 * The frosted band behind the dock row: whatever is behind it, softened, under a glass slab.
 *
 * The softening follows {@link HeroArt} rather than a GPU effect. A real blur means RenderScript or a
 * shader pass, and this repo already learned on the same TV that a whole-screen blur is not worth the
 * frames; shrinking the backdrop to a handful of pixels and letting bilinear filtering stretch it back
 * buys the same look for one small texture. The capture happens once per backdrop change, never per
 * frame, and the result is a bitmap of a few hundred bytes, not a screenshot.
 *
 * The backdrop is read straight out of the wallpaper bitmap instead of being drawn from the view
 * tree. Drawing the view meant fighting the canvas transform conventions (scale-then-translate and
 * post-multiply disagree about which side the translation lands on) and the centre-crop matrix the
 * image view applies on top; sampling the bitmap with the same arithmetic is plain and checkable.
 */
public class GlassDock extends View {

    private static final String TAG = "TvHome.GlassDock";
    /**
     * Width of the captured backdrop. The band is stretched back to screen width, so this is the
     * resolution of the frost: 64 columns over 1080p is about 27 screen pixels per texel, which reads
     * as heavy frosted glass and leaves the glass fill to do the rest.
     */
    private static final int CAPTURE_W = 64;

    private final Paint backdrop = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF dest = new RectF();
    private final Rect src = new Rect();
    private final float density;
    /** Glass corner radius, shared with the backdrop clip so the frosted edge follows the rim. */
    private float radius;
    /** The whole view the backdrop is sampled from (the activity's root FrameLayout). */
    private View sourceRoot;
    /** The softened backdrop, owned by this view and only rebuilt when the source changes. */
    private Bitmap frosted;
    private boolean sourceDirty = true;

    public GlassDock(Context context) {
        this(context, null);
    }

    /** Inflated from layout XML, which always asks for this signature. */
    public GlassDock(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 26f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // No hardware layer: this band never moves, so caching it as a texture would only add an
        // offscreen buffer. GlassView needs one because a spring drags it around; this one does not.
    }

    /** Corner radius in pixels. */
    public void setCornerRadius(float px) {
        radius = px;
        invalidate();
    }

    /**
     * The view to sample for the frosted backdrop: pass the activity's root FrameLayout. Only the child
     * that actually holds the picture is read, so the grid and the dock never end up in the frost.
     */
    public void setSource(View sourceRoot) {
        this.sourceRoot = sourceRoot;
        sourceDirty = true;
        invalidate();
    }

    /**
     * Drops the cached backdrop and captures a new one. Call this when what is behind the band changed:
     * wallpaper rotation, the crossfade to another part of the day, or the cinematic artwork swap.
     */
    public final void refreshBackdrop() {
        sourceDirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        sourceDirty = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow, same as GlassView
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        if (sourceDirty) {
            sourceDirty = false;
            rebuild();
        }

        // Frosted backdrop, clipped to the slab so the picture does not square off the corners.
        if (frosted != null && !frosted.isRecycled()) {
            canvas.save();
            canvas.clipRect(rect);
            src.set(0, 0, frosted.getWidth(), frosted.getHeight());
            dest.set(rect);
            canvas.drawBitmap(frosted, src, dest, backdrop);
            canvas.restore();
        }

        // Outer glow: three widening, fading strokes.
        for (int i = 1; i <= 3; i++) {
            glow.setStrokeWidth(i * 2f * density);
            glow.setColor((0x30 / i) << 24 | 0xFFFFFF);
            float o = i * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Specular over the top 45%: clipRect stays on the GPU (clipPath did not on this hardware), as GlassView notes.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, rim);
    }

    /**
     * Reads the wallpaper bitmap under the band, shrinks it to {@link #CAPTURE_W} columns and blurs it
     * on the CPU. The result is a small bitmap; the whole cost is a few hundred pixel reads plus a blur
     * over a few hundred pixels, and it only runs when the source changed.
     */
    private void rebuild() {
        recycleFrosted();
        if (sourceRoot == null || getWidth() <= 0 || getHeight() <= 0) return;
        int w = getWidth();
        int h = getHeight();
        int capH = Math.max(1, Math.round(CAPTURE_W * (float) h / w));
        try {
            ImageView source = picture();
            if (source == null) return;
            Bitmap bitmap = bitmapOf(source);
            if (bitmap == null || bitmap.isRecycled()) return;

            float[] map = mapping(source, bitmap);
            if (map == null) return;
            float scale = map[0], left = map[1], top = map[2];
            int[] origin = new int[2];
            source.getLocationInWindow(origin);
            int[] band = new int[2];
            getLocationInWindow(band);

            int[] px = new int[CAPTURE_W * capH];
            for (int row = 0; row < capH; row++) {
                int wy = band[1] + (int) ((row + 0.5f) * h / capH);
                int shade = scrimAlpha(wy);
                for (int col = 0; col < CAPTURE_W; col++) {
                    int wx = band[0] + (int) ((col + 0.5f) * w / CAPTURE_W);
                    float sx = (wx - origin[0] - left) / scale;
                    float sy = (wy - origin[1] - top) / scale;
                    int ix = Math.max(0, Math.min(bitmap.getWidth() - 1, (int) sx));
                    int iy = Math.max(0, Math.min(bitmap.getHeight() - 1, (int) sy));
                    px[row * CAPTURE_W + col] = darken(bitmap.getPixel(ix, iy), shade);
                }
            }
            // One pass per axis, radius 2, three times: a box pass repeated approximates a gaussian, and
            // at this resolution the whole thing is a few thousand pixels.
            for (int pass = 0; pass < 3; pass++) {
                px = boxBlur(px, CAPTURE_W, capH, 2, true);
                px = boxBlur(px, CAPTURE_W, capH, 2, false);
            }
            frosted = Bitmap.createBitmap(px, CAPTURE_W, capH, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError failure) {
            // A backdrop is decoration: on any failure the band falls back to the plain glass slab.
            Log.w(TAG, "Backdrop capture failed, drawing the glass alone", failure);
            frosted = null;
        }
    }

    /**
     * The image view that is actually on screen. The wallpaper player crossfades between two image views,
     * and the one fading out is still opaque for a moment, so the most opaque one is the visible one and
     * the last one wins a tie because that is the one drawn on top.
     */
    private ImageView picture() {
        if (!(sourceRoot instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) sourceRoot;
        ImageView best = null;
        float bestAlpha = -1f;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == this || !(child instanceof ImageView)) continue;
            ImageView image = (ImageView) child;
            if (image.getDrawable() == null || image.getWidth() <= 0) continue;
            float alpha = image.getAlpha();
            if (alpha >= bestAlpha) {
                bestAlpha = alpha;
                best = image;
            }
        }
        return best;
    }

    /** The bitmap an image view is showing, when it is a plain bitmap. */
    private static Bitmap bitmapOf(ImageView image) {
        Drawable drawable = image.getDrawable();
        if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
        return null;
    }

    /**
     * How the image view lays its bitmap out on screen: the scale it applies and where the picture
     * starts inside the view. Returns null for a scale type this cannot place.
     */
    private static float[] mapping(ImageView image, Bitmap bitmap) {
        int vw = image.getWidth();
        int vh = image.getHeight();
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return null;
        float scale;
        switch (image.getScaleType()) {
            case FIT_CENTER:
                scale = Math.min(vw / (float) bw, vh / (float) bh);
                break;
            case CENTER:
            case CENTER_INSIDE:
                scale = Math.min(1f, Math.min(vw / (float) bw, vh / (float) bh));
                break;
            case FIT_XY:
                scale = 1f;
                break;
            case MATRIX:
                return null;
            case CENTER_CROP:
            default:
                scale = Math.max(vw / (float) bw, vh / (float) bh);
                break;
        }
        return new float[]{scale, (vw - bw * scale) / 2f, (vh - bh * scale) / 2f};
    }

    /**
     * How much the legibility scrim darkens the screen at a given height. The scrim is the same gradient
     * the layout draws over the lower half of the home, so the frost carries it too and the band reads as
     * glass over that part of the picture rather than a bright bar pasted on top of it.
     */
    private int scrimAlpha(int screenY) {
        if (sourceRoot == null) return 0;
        View scrim = sourceRoot.findViewById(R.id.scrim);
        if (scrim == null || scrim.getHeight() <= 0) return 0;
        int[] at = new int[2];
        scrim.getLocationInWindow(at);
        float t = (screenY - at[1]) / (float) scrim.getHeight();
        if (t <= 0f || t >= 1f) return 0;
        // scrim_bottom.xml runs bottom to top: 00 at its top edge, 66 in the middle, B3 at the bottom.
        int top = 0x00, middle = 0x66, bottom = 0xB3;
        float a = t <= 0.5f
                ? top + (middle - top) * (t / 0.5f)
                : middle + (bottom - middle) * ((t - 0.5f) / 0.5f);
        return Math.round(a);
    }

    /** The colour a pixel turns into once the scrim's black is laid over it. */
    private static int darken(int colour, int shade) {
        if (shade <= 0) return colour;
        int keep = 255 - shade;
        return Color.argb(255,
                Color.red(colour) * keep / 255,
                Color.green(colour) * keep / 255,
                Color.blue(colour) * keep / 255);
    }

    /** One box blur pass along rows (horizontal) or columns, edges clamped. Same as HeroArt's. */
    private static int[] boxBlur(int[] src, int w, int h, int radius, boolean horizontal) {
        int[] dst = new int[src.length];
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0, a = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int p = horizontal ? src[line * w + j] : src[j * w + line];
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    a += Color.alpha(p);
                }
                dst[horizontal ? line * w + i : i * w + line] =
                        Color.argb(a / span, r / span, g / span, b / span);
            }
        }
        return dst;
    }

    private void recycleFrosted() {
        if (frosted != null && !frosted.isRecycled()) frosted.recycle();
        frosted = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        recycleFrosted();
    }
}
