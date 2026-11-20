package com.abarrato.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Descarga y muestra las fotos de los productos, con caché en memoria.
final class FotosProducto {
    private static final ExecutorService DESCARGAS = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(60);

    private FotosProducto() {}

    /**
     * Foto del producto publicada por el supermercado. Mientras carga, o si no hay foto,
     * muestra el emoji de su categoría.
     */
    static FrameLayout vista(Context contexto, String url, String nombre, String emoji, int tamanoEmoji) {
        FrameLayout marco = new FrameLayout(contexto);
        TextView mensaje = new TextView(contexto);
        mensaje.setGravity(Gravity.CENTER);
        mensaje.setText(emoji);
        mensaje.setTextSize(tamanoEmoji);
        mensaje.setContentDescription(nombre);
        marco.addView(mensaje, new FrameLayout.LayoutParams(-1, -1));
        if (url == null || !url.startsWith("https://")) {
            return marco;
        }

        Bitmap guardada = CACHE.get(url);
        if (guardada != null) {
            mostrar(marco, guardada, nombre);
            return marco;
        }
        marco.post(() -> DESCARGAS.execute(() -> {
            Bitmap imagen = CACHE.get(url);
            if (imagen == null) {
                imagen = descargar(url);
                if (imagen != null) {
                    CACHE.put(url, imagen);
                }
            }
            Bitmap resultado = imagen;
            marco.post(() -> {
                if (resultado != null) {
                    mostrar(marco, resultado, nombre);
                }
            });
        }));
        return marco;
    }

    // Pone la imagen descargada en su marco.
    private static void mostrar(FrameLayout marco, Bitmap imagen, String nombre) {
        Context contexto = marco.getContext();
        ImageView vista = new ImageView(contexto);
        vista.setScaleType(ImageView.ScaleType.FIT_CENTER);
        vista.setImageBitmap(imagen);
        vista.setContentDescription(contexto.getString(R.string.prices_image_description, nombre));
        marco.removeAllViews();
        marco.addView(vista, new FrameLayout.LayoutParams(-1, -1));
    }

    // Descarga una imagen con tiempo máximo y tamaño máximo de 2 MB.
    private static Bitmap descargar(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(6000);
            try (InputStream entrada = connection.getInputStream()) {
                ByteArrayOutputStream datos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int cantidad;
                while ((cantidad = entrada.read(buffer)) != -1) {
                    if (datos.size() + cantidad > 2 * 1024 * 1024) {
                        return null;
                    }
                    datos.write(buffer, 0, cantidad);
                }
                byte[] bytes = datos.toByteArray();
                BitmapFactory.Options medidas = new BitmapFactory.Options();
                medidas.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, medidas);
                BitmapFactory.Options escala = new BitmapFactory.Options();
                escala.inSampleSize = 1;
                while (Math.max(medidas.outWidth, medidas.outHeight) / escala.inSampleSize > 320) {
                    escala.inSampleSize *= 2;
                }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, escala);
            }
        } catch (IOException error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
