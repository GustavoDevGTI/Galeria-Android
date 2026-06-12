package com.galeria.android;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageEditActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditorView editor;
    private Uri sourceUri;
    private String sourceName;
    private String mimeType;
    private Button brushButton;
    private Button cropButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sourceUri = Uri.parse(getIntent().getStringExtra("uri"));
        sourceName = getIntent().getStringExtra("name");
        mimeType = getIntent().getStringExtra("mime");
        buildLayout();
        loadImage();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 10), statusBarHeight() + Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        bar.setBackgroundColor(Color.BLACK);

        Button back = Ui.button(this, "Voltar");
        back.setOnClickListener(view -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 86), Ui.dp(this, 42)));

        TextView title = Ui.title(this, "Editar imagem", 18);
        title.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Ui.dp(this, 10);
        bar.addView(title, titleParams);

        Button save = Ui.button(this, "Salvar");
        save.setOnClickListener(view -> saveEditedImage());
        bar.addView(save, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 42)));
        root.addView(bar);

        FrameLayout stage = new FrameLayout(this);
        editor = new EditorView(this);
        stage.addView(editor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), navigationBarHeight() + Ui.dp(this, 8));
        tools.setBackgroundColor(Color.BLACK);

        brushButton = Ui.button(this, "Pincel");
        brushButton.setOnClickListener(view -> {
            editor.setBrushEnabled(!editor.isBrushEnabled());
            refreshToolButtons();
        });
        cropButton = Ui.button(this, "Cortar");
        cropButton.setOnClickListener(view -> {
            editor.setCropEnabled(!editor.isCropEnabled());
            refreshToolButtons();
        });
        Button clear = Ui.button(this, "Limpar");
        clear.setOnClickListener(view -> editor.clearDrawing());

        tools.addView(brushButton, toolParams());
        tools.addView(cropButton, toolParams());
        tools.addView(clear, toolParams());
        root.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refreshToolButtons();
    }

    private LinearLayout.LayoutParams toolParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        params.leftMargin = Ui.dp(this, 5);
        params.rightMargin = Ui.dp(this, 5);
        return params;
    }

    private void refreshToolButtons() {
        brushButton.setAlpha(editor.isBrushEnabled() ? 1f : 0.55f);
        cropButton.setAlpha(editor.isCropEnabled() ? 1f : 0.55f);
    }

    private void loadImage() {
        executor.execute(() -> {
            try {
                Bitmap bitmap = decodeBitmap(sourceUri, 3000);
                runOnUiThread(() -> editor.setBitmap(bitmap));
            } catch (Exception exception) {
                runOnUiThread(() -> Ui.toast(this, "Não foi possível abrir a imagem."));
            }
        });
    }

    private Bitmap decodeBitmap(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        int sample = 1;
        while ((bounds.outWidth / sample) > maxSide || (bounds.outHeight / sample) > maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private void saveEditedImage() {
        executor.execute(() -> {
            try {
                Bitmap edited = editor.renderEditedBitmap();
                if (edited == null) {
                    throw new IllegalStateException("bitmap");
                }
                Uri saved = saveBitmapToGallery(edited);
                runOnUiThread(() -> {
                    Ui.toast(this, saved != null ? "Imagem editada salva." : "Não foi possível salvar.");
                    if (saved != null) {
                        finish();
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> Ui.toast(this, "Não foi possível salvar."));
            }
        });
    }

    private Uri saveBitmapToGallery(Bitmap bitmap) throws Exception {
        String outputName = editedName();
        boolean png = isPng();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, outputName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, png ? "image/png" : "image/jpeg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Galeria Editada/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    return null;
                }
                bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 94, output);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Galeria Editada");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, outputName);
        int count = 1;
        while (target.exists()) {
            target = new File(dir, count + "-" + outputName);
            count++;
        }
        try (FileOutputStream output = new FileOutputStream(target)) {
            bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 94, output);
        }
        MediaScannerConnection.scanFile(this, new String[] { target.getAbsolutePath() }, null, null);
        return Uri.fromFile(target);
    }

    private boolean isPng() {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.US);
        return mime.contains("png") || name.endsWith(".png");
    }

    private String editedName() {
        String name = sourceName == null || sourceName.trim().isEmpty() ? "imagem" : sourceName.trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = isPng() ? ".png" : ".jpg";
        return base + "-editada" + ext;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : Ui.dp(this, 24);
    }

    private int navigationBarHeight() {
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : Ui.dp(this, 24);
    }

    private static final class EditorView extends View {
        private final ArrayList<Path> paths = new ArrayList<>();
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix bitmapToView = new Matrix();
        private final Matrix viewToBitmap = new Matrix();
        private final RectF imageRect = new RectF();
        private Bitmap bitmap;
        private Path activePath;
        private boolean brushEnabled = true;
        private boolean cropEnabled;

        EditorView(Activity activity) {
            super(activity);
            setBackgroundColor(Color.BLACK);
            brushPaint.setColor(Color.WHITE);
            brushPaint.setStyle(Paint.Style.STROKE);
            brushPaint.setStrokeWidth(8f);
            brushPaint.setStrokeCap(Paint.Cap.ROUND);
            brushPaint.setStrokeJoin(Paint.Join.ROUND);
            cropPaint.setColor(Color.WHITE);
            cropPaint.setStyle(Paint.Style.STROKE);
            cropPaint.setStrokeWidth(3f);
        }

        void setBitmap(Bitmap bitmap) {
            this.bitmap = bitmap;
            invalidate();
        }

        boolean isBrushEnabled() {
            return brushEnabled;
        }

        void setBrushEnabled(boolean enabled) {
            brushEnabled = enabled;
        }

        boolean isCropEnabled() {
            return cropEnabled;
        }

        void setCropEnabled(boolean enabled) {
            cropEnabled = enabled;
            invalidate();
        }

        void clearDrawing() {
            paths.clear();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) {
                return;
            }
            updateMatrices();
            canvas.drawBitmap(bitmap, bitmapToView, bitmapPaint);
            canvas.save();
            canvas.concat(bitmapToView);
            for (Path path : paths) {
                canvas.drawPath(path, brushPaint);
            }
            if (activePath != null) {
                canvas.drawPath(activePath, brushPaint);
            }
            canvas.restore();
            if (cropEnabled) {
                canvas.drawRect(cropRectView(), cropPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!brushEnabled || bitmap == null) {
                return true;
            }
            updateMatrices();
            float[] point = new float[] { event.getX(), event.getY() };
            viewToBitmap.mapPoints(point);
            point[0] = Math.max(0, Math.min(bitmap.getWidth(), point[0]));
            point[1] = Math.max(0, Math.min(bitmap.getHeight(), point[1]));
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                activePath = new Path();
                activePath.moveTo(point[0], point[1]);
                invalidate();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE && activePath != null) {
                activePath.lineTo(point[0], point[1]);
                invalidate();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP && activePath != null) {
                activePath.lineTo(point[0], point[1]);
                paths.add(activePath);
                activePath = null;
                invalidate();
                return true;
            }
            return true;
        }

        Bitmap renderEditedBitmap() {
            if (bitmap == null) {
                return null;
            }
            Bitmap output = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(output);
            for (Path path : paths) {
                canvas.drawPath(path, brushPaint);
            }
            if (!cropEnabled) {
                return output;
            }
            RectF crop = cropRectBitmap();
            int left = Math.max(0, Math.round(crop.left));
            int top = Math.max(0, Math.round(crop.top));
            int right = Math.min(output.getWidth(), Math.round(crop.right));
            int bottom = Math.min(output.getHeight(), Math.round(crop.bottom));
            return Bitmap.createBitmap(output, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }

        private void updateMatrices() {
            if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
                return;
            }
            float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            float dx = (getWidth() - bitmap.getWidth() * scale) / 2f;
            float dy = (getHeight() - bitmap.getHeight() * scale) / 2f;
            bitmapToView.reset();
            bitmapToView.postScale(scale, scale);
            bitmapToView.postTranslate(dx, dy);
            bitmapToView.invert(viewToBitmap);
            imageRect.set(dx, dy, dx + bitmap.getWidth() * scale, dy + bitmap.getHeight() * scale);
        }

        private RectF cropRectView() {
            updateMatrices();
            float insetX = imageRect.width() * 0.08f;
            float insetY = imageRect.height() * 0.08f;
            return new RectF(imageRect.left + insetX, imageRect.top + insetY, imageRect.right - insetX, imageRect.bottom - insetY);
        }

        private RectF cropRectBitmap() {
            RectF viewRect = cropRectView();
            RectF bitmapRect = new RectF(viewRect);
            viewToBitmap.mapRect(bitmapRect);
            return bitmapRect;
        }
    }
}
