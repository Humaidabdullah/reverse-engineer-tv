package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the liquid-glass band behind the dock row, as on the Control Center tiles: a smoky see-through fill
 * under a specular rim (GlassEdgeDrawable), and spaces its square icons.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** A light grey wash over the smoky fill (Home settings: Liquid glass > Dock tint). */
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** An even hairline all round, as on the original dock, so the band's shape reads on any wallpaper;
     *  the glass rim alone fades out along the sides. */
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect bounds = new Rect();
    /** The rim, rebuilt only when the corner radius changes (icon size in Home settings). */
    private GlassEdgeDrawable rim;
    private float rimRadius;
    /** Band padding around the icons. */
    static final float PAD_DP = 14f;

    private final LauncherPrefs prefs;

    DockDecoration(int dockSize, float density, LauncherPrefs prefs) {
        this.dockSize = dockSize;
        this.density = density;
        this.prefs = prefs;
        band.setColor(prefs.uiGlassFill());
        int t = Math.max(0, Math.min(100, prefs.getInt(LauncherPrefs.DOCK_TINT, LauncherPrefs.DEF_DOCK_TINT)));
        tint.setColor((Math.round(t * 2.55f) << 24) | 0xD8D8DC);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(density);
        outline.setColor(0x38FFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        // The gap between icons is part of each item's width (see AppAdapter), not an offset here.
        outRect.top = (int) (6 * density);
        outRect.bottom = (int) (12 * density);
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft() + child.getTranslationX();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = PAD_DP * density;
        rect.inset(-pad, -pad);
        // A scrolled dock has icons past its ends: the band stays the width of the list.
        float half = density / 2f;
        rect.left = Math.max(rect.left, half);
        rect.right = Math.min(rect.right, parent.getWidth() - half);
        float radius = 0.225f * AppAdapter.DOCK_DP * density + pad;
        c.drawRoundRect(rect, radius, radius, band);
        if (tint.getAlpha() > 0) c.drawRoundRect(rect, radius, radius, tint);
        c.drawRoundRect(rect, radius, radius, outline);
        if (rim == null || rimRadius != radius) {
            rim = prefs.uiRim(density, false, radius);
            rimRadius = radius;
        }
        rect.round(bounds);
        rim.setBounds(bounds);
        rim.draw(c);
    }
}
