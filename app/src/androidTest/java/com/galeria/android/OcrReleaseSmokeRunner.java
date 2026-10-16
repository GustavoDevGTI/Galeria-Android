package com.galeria.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

/** SDK-only black-box smoke: no app symbols, AndroidX, Kotlin or test hooks.
 * It runs against the real optimized release with a compatible test signature. */
public final class OcrReleaseSmokeRunner extends Instrumentation {
    private interface Condition { boolean ready(); }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", "longPressRecognizesAndCopiesTextInTheActualViewer");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        Bundle result = new Bundle();
        try {
            check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"),
                "Smoke exclusivo para emulador dedicado");
            verifyViewer();
            sendStatus(0, status);
            result.putString("stream", "\nOK (1 test)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            status.putString("stack", stack.toString());
            sendStatus(-2, status);
            result.putString("stream", "\nFAILURES!!!\n" + stack);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void verifyViewer() throws Exception {
        Context context = getTargetContext();
        File photo = File.createTempFile("release-ocr-smoke-", ".png", context.getCacheDir());
        Bitmap bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888);
        Activity activity = null;
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(44f);
            canvas.drawText("DOCUMENTO ALFA BETA GALERIA", 60f, 440f, paint);
            canvas.drawText("TEXTO COPIAVEL TESTE ORIGINAL", 60f, 600f, paint);
            canvas.drawText("PAGINA CLARA LEITURA LOCAL", 60f, 760f, paint);
            canvas.drawText("IMAGEM PRESERVADA SEM ALTERACOES", 60f, 920f, paint);
            try (FileOutputStream out = new FileOutputStream(photo)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "Falha ao criar imagem de teste");
            }
            bitmap.recycle();
            byte[] original = readBytes(photo);
            Intent intent = new Intent().setClassName(context.getPackageName(), context.getPackageName() + ".DetailActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("uri", android.net.Uri.fromFile(photo).toString())
                .putExtra("mime", "image/png").putExtra("name", photo.getName());
            activity = startActivitySync(intent);
            Activity host = activity;
            await("A foto não foi exibida", () -> {
                boolean[] ready = {false};
                runOnMainSync(() -> ready[0] = hasLoadedPhoto(host.getWindow().getDecorView()));
                return ready[0];
            });
            Rect bounds = new Rect();
            await("Janela da foto ainda não está acessível", () -> {
                AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root == null || !context.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
                root.getBoundsInScreen(bounds);
                return !bounds.isEmpty();
            });
            long down = SystemClock.uptimeMillis();
            touch(down, MotionEvent.ACTION_DOWN, bounds);
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L);
            touch(down, MotionEvent.ACTION_UP, bounds);
            await("OCR não reconheceu o documento na foto; verificar inicialização release", () ->
                textNode("DOCUMENTO") != null && textNode("ALFA") != null &&
                textNode("GALERIA") != null && textNode("COPIAVEL") != null);
            String selectAll = string(context, "action_select_all");
            String copy = string(context, "ocr_copy_selection");
            await("Selecionar tudo não está acessível", () -> textNode(selectAll) != null);
            check(textNode(selectAll).performAction(AccessibilityNodeInfo.ACTION_CLICK), "Seleção total falhou");
            await("Copiar não está acessível", () -> textNode(copy) != null && textNode(copy).isEnabled());
            check(textNode(copy).performAction(AccessibilityNodeInfo.ACTION_CLICK), "Clique em Copiar falhou");
            waitForIdleSync();
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            check(clipboard.getPrimaryClip() != null, "OCR não colocou texto no clipboard");
            String copied = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context).toString();
            for (String word : new String[]{"DOCUMENTO", "ALFA", "BETA", "GALERIA", "COPIAVEL"}) {
                check(copied.contains(word), "Texto copiado não contém " + word + ": " + copied);
            }
            check(Arrays.equals(original, readBytes(photo)), "O OCR alterou a foto");
            Bitmap capture = getUiAutomation().takeScreenshot();
            if (capture != null) {
                try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "ocr-release-smoke.png"))) {
                    capture.compress(Bitmap.CompressFormat.PNG, 100, out);
                } finally { capture.recycle(); }
            }
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
            if (activity != null) {
                Activity host = activity;
                runOnMainSync(host::finish);
            }
            photo.delete();
        }
    }

    private void touch(long down, int action, Rect bounds) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { sendPointerSync(event); } finally { event.recycle(); }
    }

    private AccessibilityNodeInfo textNode(String text) {
        return textNode(getUiAutomation().getRootInActiveWindow(), text);
    }

    // ExploreByTouchHelper exposes OCR words as virtual children, not a native
    // TextView search index. Walk those public children just like a screen reader.
    private AccessibilityNodeInfo textNode(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (text.contentEquals(node.getText() == null ? "" : node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = textNode(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static String string(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        check(id != 0, "Recurso de interface ausente: " + name);
        return context.getString(id);
    }

    private static boolean hasLoadedPhoto(View view) {
        if (view instanceof ImageView && view.getWidth() > 200 && view.getHeight() > 200 &&
                ((ImageView) view).getDrawable() != null) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasLoadedPhoto(group.getChildAt(i))) return true;
        }
        return false;
    }

    private static void await(String message, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 30_000L;
        while (!condition.ready()) {
            check(SystemClock.uptimeMillis() < deadline, message);
            SystemClock.sleep(100L);
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static byte[] readBytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
