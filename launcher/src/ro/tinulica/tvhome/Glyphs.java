package ro.tinulica.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Icons for the Control Center, drawn in code in the manner of Apple's SF Symbols
 * (the "medium" weight: even 2 pt strokes on a 24 pt grid, round caps and joins,
 * optical centring). The drawings are our own; no Apple artwork is used.
 */
final class Glyphs {

    private Glyphs() {
    }

    /**
     * The icon for `kind`, white, at `size` px: a PNG the user pushed to files/icons/<kind>.png
     * (exported from the SF Symbols app, say), else the SF Symbols-style vector in res/drawable
     * (Framework7 Icons, MIT), else our own drawing. Whatever the source, the visible shape is
     * measured and centred optically, so every symbol sits in the middle of its button.
     */
    static Bitmap symbol(android.content.Context context, String kind, int size) {
        Bitmap raw = null;
        java.io.File dir = context.getExternalFilesDir("icons");
        java.io.File own = dir != null ? new java.io.File(dir, kind + ".png") : null;
        if (own != null && own.exists()) {
            raw = android.graphics.BitmapFactory.decodeFile(own.getAbsolutePath());
            if (raw != null) {
                raw = whitened(raw);
            }
        }
        if (raw == null) {
            int id = context.getResources().getIdentifier("sym_" + kind, "drawable", context.getPackageName());
            android.graphics.drawable.Drawable d = id != 0 ? context.getDrawable(id) : null;
            if (d != null) {
                int big = size * 2;
                raw = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, big, big);
                d.setTint(Color.WHITE);
                d.draw(new Canvas(raw));
            }
        }
        if (raw == null) {
            return draw(kind, size);
        }
        return centred(raw, size);
    }

    /** Keeps the alpha, paints everything white (so a black-on-transparent export works too). */
    private static Bitmap whitened(Bitmap source) {
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN));
        new Canvas(out).drawBitmap(source, 0, 0, paint);
        return out;
    }

    /** Scales the visible (non-transparent) part to 80% of `size` and centres it. */
    private static Bitmap centred(Bitmap raw, int size) {
        int w = raw.getWidth();
        int h = raw.getHeight();
        int[] px = new int[w * h];
        raw.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((px[y * w + x] >>> 24) & 0xFF) > 24) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (right < left) {
            return out;
        }
        float bw = right - left + 1;
        float bh = bottom - top + 1;
        float target = size * 0.80f;
        float scale = Math.min(target / bw, target / bh);
        float dw = bw * scale;
        float dh = bh * scale;
        RectF dst = new RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        new Canvas(out).drawBitmap(raw, new android.graphics.Rect(left, top, right + 1, bottom + 1), dst, paint);
        return out;
    }

    static Bitmap draw(String kind, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.09f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        float s = size;
        switch (kind) {
            case "power": {
                // power: open ring with a bar through the gap (like SF "power")
                canvas.drawArc(new RectF(s * 0.17f, s * 0.2f, s * 0.83f, s * 0.86f), -55, 290, false, stroke);
                canvas.drawLine(s * 0.5f, s * 0.1f, s * 0.5f, s * 0.5f, stroke);
                break;
            }
            case "hdmi": {
                // tv.and.hifispeaker-ish: a wide plug with three pins and a cable
                Path plug = new Path();
                plug.moveTo(s * 0.16f, s * 0.36f);
                plug.lineTo(s * 0.84f, s * 0.36f);
                plug.lineTo(s * 0.84f, s * 0.56f);
                plug.lineTo(s * 0.74f, s * 0.68f);
                plug.lineTo(s * 0.26f, s * 0.68f);
                plug.lineTo(s * 0.16f, s * 0.56f);
                plug.close();
                canvas.drawPath(plug, stroke);
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.36f + i * 0.14f);
                    canvas.drawLine(x, s * 0.46f, x, s * 0.56f, stroke);
                }
                canvas.drawLine(s * 0.5f, s * 0.68f, s * 0.5f, s * 0.84f, stroke);
                break;
            }
            case "antenna": {
                // antenna.radiowaves.left.and.right
                canvas.drawCircle(s * 0.5f, s * 0.46f, s * 0.07f, fill);
                canvas.drawLine(s * 0.5f, s * 0.52f, s * 0.5f, s * 0.88f, stroke);
                canvas.drawArc(new RectF(s * 0.28f, s * 0.24f, s * 0.72f, s * 0.68f), 135, 90, false, stroke);
                canvas.drawArc(new RectF(s * 0.28f, s * 0.24f, s * 0.72f, s * 0.68f), -45, 90, false, stroke);
                canvas.drawArc(new RectF(s * 0.12f, s * 0.08f, s * 0.88f, s * 0.84f), 135, 90, false, stroke);
                canvas.drawArc(new RectF(s * 0.12f, s * 0.08f, s * 0.88f, s * 0.84f), -45, 90, false, stroke);
                break;
            }
            case "tv": {
                // tv
                canvas.drawRoundRect(new RectF(s * 0.1f, s * 0.18f, s * 0.9f, s * 0.7f), s * 0.1f, s * 0.1f, stroke);
                canvas.drawLine(s * 0.3f, s * 0.86f, s * 0.7f, s * 0.86f, stroke);
                break;
            }
            case "wifi": {
                // wifi
                for (int i = 0; i < 3; i++) {
                    float r = s * (0.16f + i * 0.17f);
                    canvas.drawArc(new RectF(s * 0.5f - r, s * 0.74f - r, s * 0.5f + r, s * 0.74f + r),
                            222, 96, false, stroke);
                }
                canvas.drawCircle(s * 0.5f, s * 0.76f, s * 0.065f, fill);
                break;
            }
            case "bluetooth": {
                Path rune = new Path();
                rune.moveTo(s * 0.28f, s * 0.3f);
                rune.lineTo(s * 0.7f, s * 0.66f);
                rune.lineTo(s * 0.5f, s * 0.84f);
                rune.lineTo(s * 0.5f, s * 0.16f);
                rune.lineTo(s * 0.7f, s * 0.34f);
                rune.lineTo(s * 0.28f, s * 0.7f);
                canvas.drawPath(rune, stroke);
                break;
            }
            case "moon": {
                // moon.fill, tilted like SF's
                Path moon = new Path();
                moon.addCircle(s * 0.48f, s * 0.52f, s * 0.34f, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(s * 0.66f, s * 0.38f, s * 0.3f, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                canvas.drawPath(moon, fill);
                break;
            }
            case "timer": {
                canvas.drawCircle(s * 0.5f, s * 0.54f, s * 0.33f, stroke);
                canvas.drawLine(s * 0.5f, s * 0.54f, s * 0.5f, s * 0.33f, stroke);
                canvas.drawLine(s * 0.5f, s * 0.54f, s * 0.65f, s * 0.6f, stroke);
                canvas.drawLine(s * 0.4f, s * 0.12f, s * 0.6f, s * 0.12f, stroke);
                break;
            }
            case "image": {
                // photo
                canvas.drawRoundRect(new RectF(s * 0.12f, s * 0.2f, s * 0.88f, s * 0.8f), s * 0.1f, s * 0.1f, stroke);
                Path hills = new Path();
                hills.moveTo(s * 0.14f, s * 0.72f);
                hills.lineTo(s * 0.38f, s * 0.48f);
                hills.lineTo(s * 0.54f, s * 0.62f);
                hills.lineTo(s * 0.64f, s * 0.54f);
                hills.lineTo(s * 0.86f, s * 0.74f);
                canvas.drawPath(hills, stroke);
                canvas.drawCircle(s * 0.66f, s * 0.38f, s * 0.065f, fill);
                break;
            }
            case "access": {
                // figure.stand in a circle (accessibility)
                canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.42f, stroke);
                canvas.drawCircle(s * 0.5f, s * 0.3f, s * 0.065f, fill);
                canvas.drawLine(s * 0.3f, s * 0.43f, s * 0.7f, s * 0.43f, stroke);
                canvas.drawLine(s * 0.5f, s * 0.43f, s * 0.5f, s * 0.58f, stroke);
                canvas.drawLine(s * 0.5f, s * 0.58f, s * 0.38f, s * 0.76f, stroke);
                canvas.drawLine(s * 0.5f, s * 0.58f, s * 0.62f, s * 0.76f, stroke);
                break;
            }
            case "search": {
                // magnifyingglass
                canvas.drawCircle(s * 0.44f, s * 0.44f, s * 0.26f, stroke);
                canvas.drawLine(s * 0.63f, s * 0.63f, s * 0.86f, s * 0.86f, stroke);
                break;
            }
            case "camera": {
                // video.fill
                Path body = new Path();
                body.addRoundRect(new RectF(s * 0.1f, s * 0.3f, s * 0.62f, s * 0.7f), s * 0.08f, s * 0.08f, Path.Direction.CW);
                Path lens = new Path();
                lens.moveTo(s * 0.66f, s * 0.44f);
                lens.lineTo(s * 0.9f, s * 0.3f);
                lens.lineTo(s * 0.9f, s * 0.7f);
                lens.lineTo(s * 0.66f, s * 0.56f);
                lens.close();
                canvas.drawPath(body, fill);
                canvas.drawPath(lens, fill);
                break;
            }
            case "folder": {
                // folder
                Path folder = new Path();
                folder.moveTo(s * 0.1f, s * 0.26f);
                folder.lineTo(s * 0.4f, s * 0.26f);
                folder.lineTo(s * 0.5f, s * 0.38f);
                folder.lineTo(s * 0.9f, s * 0.38f);
                folder.lineTo(s * 0.9f, s * 0.78f);
                folder.lineTo(s * 0.1f, s * 0.78f);
                folder.close();
                canvas.drawPath(folder, stroke);
                break;
            }
            case "gear": {
                // gearshape.fill
                Path gear = new Path();
                float cx = s / 2f;
                float cy = s / 2f;
                float outer = s * 0.42f;
                float inner = s * 0.32f;
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a0 = Math.PI * 2 * i / (teeth * 2);
                    double a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                    float r = i % 2 == 0 ? outer : inner;
                    float x0 = cx + (float) Math.cos(a0 - 0.1) * r;
                    float y0 = cy + (float) Math.sin(a0 - 0.1) * r;
                    float x1 = cx + (float) Math.cos(a1 + 0.1) * r;
                    float y1 = cy + (float) Math.sin(a1 + 0.1) * r;
                    if (i == 0) {
                        gear.moveTo(x0, y0);
                    } else {
                        gear.lineTo(x0, y0);
                    }
                    gear.lineTo(x1, y1);
                }
                gear.close();
                Path hole = new Path();
                hole.addCircle(cx, cy, s * 0.14f, Path.Direction.CW);
                gear.op(hole, Path.Op.DIFFERENCE);
                canvas.drawPath(gear, fill);
                break;
            }
            case "list":
            default: {
                // square.grid.2x2 (all apps)
                float r = s * 0.06f;
                canvas.drawRoundRect(new RectF(s * 0.14f, s * 0.14f, s * 0.46f, s * 0.46f), r, r, stroke);
                canvas.drawRoundRect(new RectF(s * 0.54f, s * 0.14f, s * 0.86f, s * 0.46f), r, r, stroke);
                canvas.drawRoundRect(new RectF(s * 0.14f, s * 0.54f, s * 0.46f, s * 0.86f), r, r, stroke);
                canvas.drawRoundRect(new RectF(s * 0.54f, s * 0.54f, s * 0.86f, s * 0.86f), r, r, stroke);
                break;
            }
        }
        return bitmap;
    }
}
