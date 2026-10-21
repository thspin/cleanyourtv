package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The focus ring: one thin, bright stroke with a soft halo under it, drawn around whichever tile
 * has the focus and moved with spring animations between tiles.
 *
 * It is a ring and nothing else. An earlier version painted a whole glass slab over the focused
 * tile, which fought the tile's own shadow and doubled its edge; a single hairline says "this one"
 * on every element of the screen and costs one stroke to draw.
 */
public class GlassView extends View {

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Halo colour as 0xRRGGBB: warm white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xF5F0EA;
    /** Halo strength: it stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x1F;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2f * density);
        rim.setColor(0xF2F5F0EA);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the ring as a texture. The spring only changes translation, so the GPU can
        // move it without replaying the strokes on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the ring and its halo; 0 restores the plain warm white. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xF5F0EA;
            glowAlpha = 0x1F;
            rim.setColor(0xF2F5F0EA);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x40;
            // The rim is the accent lifted towards white, so the edge still reads as a ring, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rim.setColor(0xF2000000 | (r << 16) | (g << 8) | b);
        }
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the halo, the same inset the tile's shadow uses
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // The halo: one soft stroke under the ring, so the tile reads as lifted off the surface.
        glow.setStrokeWidth(5f * density);
        glow.setColor((glowAlpha << 24) | (glowRgb & 0xFFFFFF));
        canvas.drawRoundRect(rect, radius, radius, glow);

        // The ring: one thin, bright stroke.
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
