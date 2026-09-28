package com.codex.yybpoints;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fetches only the profile image URL supplied by the target app. */
final class AvatarLoader {
    private AvatarLoader() { }

    static void load(Activity activity, String address, ImageView target, Runnable onLoaded) {
        new Thread(() -> {
            Bitmap avatar = download(address);
            if (avatar == null) return;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) {
                    avatar.recycle();
                    return;
                }
                target.setImageBitmap(avatar);
                onLoaded.run();
            });
        }, "yyb-avatar-loader").start();
    }

    private static Bitmap download(String address) {
        HttpURLConnection connection = null;
        try {
            URL source = new URL(address);
            if ("http".equalsIgnoreCase(source.getProtocol())) {
                source = new URL("https", source.getHost(), source.getPort(), source.getFile());
            }
            if (!"https".equalsIgnoreCase(source.getProtocol())) return null;
            connection = (HttpURLConnection) source.openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200 || connection.getContentLength() > 1_000_000) {
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[8_192];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    if (output.size() + read > 1_000_000) return null;
                    output.write(chunk, 0, read);
                }
            }
            byte[] data = output.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sampleSize = 1;
            while (options.outWidth / sampleSize > 256
                    || options.outHeight / sampleSize > 256) {
                sampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            Bitmap original = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (original == null) return null;
            Bitmap scaled = Bitmap.createScaledBitmap(original, 128, 128, true);
            Bitmap rounded = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            new Canvas(rounded).drawCircle(64, 64, 64, paint);
            if (scaled != original) original.recycle();
            scaled.recycle();
            return rounded;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
