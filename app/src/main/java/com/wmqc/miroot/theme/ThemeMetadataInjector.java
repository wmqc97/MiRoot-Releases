package com.wmqc.miroot.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.wmqc.miroot.lyrics.LogHelper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * 向 rearscreen 主题 zip 注入 {@code var_config.xml} 与根目录 {@value AiWallpaperThemeHelper#THEME_EFFECT_PNG_ENTRY}（主题预览图）。
 * 写入的 {@code WidgetConfig} 对齐官方形态：{@code version="1"}、{@code author}、{@code name}、{@code description}（主题说明）。
 * 不写入 {@code des}：更新已有包时保留包内原有 {@code des}；新建 var_config 时仅含 {@code description}。
 */
public final class ThemeMetadataInjector {

    private static final String TAG = "ThemeMetadataInjector";

    public static final class InjectResult {
        /** 最终应告知用户的路径或 Uri 字符串（覆盖成功则为原 ref） */
        public final String resultRef;
        /** 是否已就地覆盖原主题文件 */
        public final boolean overwritten;

        public InjectResult(String resultRef, boolean overwritten) {
            this.resultRef = resultRef;
            this.overwritten = overwritten;
        }
    }

    private ThemeMetadataInjector() {}

    /**
     * @param themeFileRef 主题 zip 的路径或 {@code content://} Uri 字符串
     * @param effectImageUri 新效果图 {@code content://}，可为 null
     * @param effectImagePath 新效果图绝对路径（已解析），可为 null
     * @param reuseEffectFromZip 当 uri/path 均为空时，是否使用包内已有 effect.png；为 false 且无图则抛错
     */
    public static InjectResult inject(
            Context context,
            String themeFileRef,
            String author,
            String name,
            String description,
            @Nullable String effectImageUri,
            @Nullable String effectImagePath,
            boolean reuseEffectFromZip)
            throws Exception {
        if (themeFileRef == null || themeFileRef.isEmpty()) {
            throw new Exception("主题文件为空");
        }
        File outFolder = new File(AiWallpaperThemeHelper.THEME_METADATA_WORK_DIR);
        if (!outFolder.exists() && !outFolder.mkdirs()) {
            throw new Exception("无法创建工作目录");
        }
        String outputZipPath =
                AiWallpaperThemeHelper.THEME_METADATA_WORK_DIR
                        + "theme_injected_"
                        + System.currentTimeMillis()
                        + ".zip";

        byte[] effectPngBytes = loadImageAsPngBytes(context, effectImageUri, effectImagePath);
        if (effectPngBytes == null || effectPngBytes.length == 0) {
            if (reuseEffectFromZip) {
                effectPngBytes = AiWallpaperThemeHelper.readEffectPngBytesFromThemeZip(context, themeFileRef);
            }
        }
        if (effectPngBytes == null || effectPngBytes.length == 0) {
            throw new Exception("需要选择效果图，或主题包内包含 effect.png");
        }

        injectZipMetadataAndEffect(
                context, themeFileRef, author, name, description, effectPngBytes, outputZipPath);

        boolean overwritten = overwriteZipToThemeRef(context, themeFileRef, outputZipPath);
        String resultRef = overwritten ? themeFileRef : outputZipPath;
        if (overwritten) {
            deleteInjectedOutputQuietly(outputZipPath);
        }
        return new InjectResult(resultRef, overwritten);
    }

    private static void injectZipMetadataAndEffect(
            Context context,
            String themeFileRef,
            String author,
            String name,
            String description,
            byte[] effectPngBytes,
            String outputZipPath)
            throws Exception {
        File inputZipFile =
                AiWallpaperThemeHelper.resolveZipFileForRead(
                        context, themeFileRef, "theme_input_" + System.currentTimeMillis());
        if (inputZipFile == null || !inputZipFile.exists()) {
            throw new Exception("主题文件不存在或无法读取");
        }
        try {
            ZipFile zipFile = AiWallpaperThemeHelper.openZipFileForTheme(inputZipFile);
            ZipOutputStream zos = null;
            try {
                File outFile = new File(outputZipPath);
                File outParent = outFile.getParentFile();
                if (outParent != null && !outParent.exists()) {
                    outParent.mkdirs();
                }
                zos = new ZipOutputStream(new FileOutputStream(outFile));

                byte[] updatedVarConfigBytes = null;
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    if ("var_config.xml".equals(entryName)) {
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            updatedVarConfigBytes =
                                    updateVarConfigXmlFromStream(is, author, name, description);
                        } catch (Exception e) {
                            LogHelper.w(TAG, "var_config.xml parse failed, will create new", e);
                            updatedVarConfigBytes = null;
                        }
                        continue;
                    }

                    if (AiWallpaperThemeHelper.THEME_EFFECT_PNG_ENTRY.equals(entryName)) {
                        continue;
                    }

                    ZipEntry newEntry = new ZipEntry(entryName);
                    newEntry.setTime(entry.getTime());
                    zos.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        try (InputStream entryStream = zipFile.getInputStream(entry)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = entryStream.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }
                    }
                    zos.closeEntry();
                }

                if (updatedVarConfigBytes == null) {
                    updatedVarConfigBytes = createVarConfigXmlBytes(author, name, description);
                }

                writeZipEntryBytes(zos, "var_config.xml", updatedVarConfigBytes);
                writeZipEntryBytes(zos, AiWallpaperThemeHelper.THEME_EFFECT_PNG_ENTRY, effectPngBytes);
                zos.flush();
            } finally {
                try {
                    zipFile.close();
                } catch (Exception ignored) {
                }
                if (zos != null) {
                    try {
                        zos.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } finally {
            AiWallpaperThemeHelper.deleteThemeMetadataTempSourceZipQuietly(inputZipFile);
        }
    }

    private static void writeZipEntryBytes(ZipOutputStream zos, String entryName, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        if (bytes != null && bytes.length > 0) {
            zos.write(bytes);
        }
        zos.closeEntry();
    }

    private static boolean overwriteZipToThemeRef(Context context, String themeFileRef, String newZipPath)
            throws Exception {
        if (themeFileRef == null || themeFileRef.isEmpty() || newZipPath == null || newZipPath.isEmpty()) {
            return false;
        }
        File newZipFile = new File(newZipPath);
        if (!newZipFile.exists() || !newZipFile.isFile() || newZipFile.length() == 0) {
            return false;
        }

        if (themeFileRef.startsWith("content://")) {
            Uri uri = Uri.parse(themeFileRef);
            if (uri == null) {
                return false;
            }
            // 小米文件管理器等 FileProvider 通常只授只读权限，openOutputStream 会 SecurityException；
            // 捕获后返回 false，由 inject() 保留 theme_injected_*.zip 新路径供用户取用。
            try {
                OutputStream os = context.getContentResolver().openOutputStream(uri, "wt");
                if (os == null) {
                    os = context.getContentResolver().openOutputStream(uri, "w");
                }
                if (os == null) {
                    return false;
                }
                try (OutputStream out = os;
                        InputStream is = new FileInputStream(newZipFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    out.flush();
                    return true;
                }
            } catch (SecurityException e) {
                LogHelper.w(TAG, "overwriteZipToThemeRef content:// denied (no write grant)", e);
                return false;
            } catch (IllegalArgumentException | IllegalStateException e) {
                LogHelper.w(TAG, "overwriteZipToThemeRef content:// invalid state", e);
                return false;
            } catch (IOException e) {
                LogHelper.w(TAG, "overwriteZipToThemeRef content:// io", e);
                return false;
            }
        }

        File target = resolveZipFileForWrite(themeFileRef);
        if (target == null) {
            return false;
        }
        try (InputStream is = new FileInputStream(newZipFile);
                OutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            os.flush();
            return target.exists()
                    && target.length() == newZipFile.length()
                    && target.length() > 0;
        }
    }

    @Nullable
    private static File resolveZipFileForWrite(String themeFileRef) {
        if (themeFileRef == null || themeFileRef.isEmpty()) {
            return null;
        }
        String ref = themeFileRef;
        if (ref.startsWith("file://")) {
            try {
                ref = Uri.parse(ref).getPath();
            } catch (Exception ignored) {
            }
        }
        if (ref != null && ref.startsWith("/")) {
            File f = new File(ref);
            if (f.exists() && f.isFile()) {
                return f;
            }
            File fZip = new File(ref + ".zip");
            if (fZip.exists() && fZip.isFile()) {
                return fZip;
            }
        }
        return null;
    }

    private static void deleteInjectedOutputQuietly(String outputZipPath) {
        if (outputZipPath == null || outputZipPath.isEmpty()) {
            return;
        }
        try {
            File f = new File(outputZipPath);
            if (!f.exists() || !f.isFile()) {
                return;
            }
            String abs = f.getAbsolutePath();
            if (!abs.startsWith(AiWallpaperThemeHelper.THEME_METADATA_WORK_DIR)) {
                return;
            }
            String n = f.getName();
            if (n.startsWith("theme_injected_") && n.endsWith(".zip")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static byte[] loadImageAsPngBytes(
            Context context, @Nullable String effectImageUri, @Nullable String effectImagePath)
            throws Exception {
        Bitmap bmp = decodeSampledBitmapForMaxDim(context, effectImageUri, effectImagePath, 1024);
        if (bmp == null) {
            return null;
        }

        int width = bmp.getWidth();
        int height = bmp.getHeight();
        if (width <= 0 || height <= 0) {
            throw new Exception("效果图尺寸异常");
        }

        Bitmap scaled = bmp;
        int maxDim = 1024;
        if (width > maxDim || height > maxDim) {
            float scale = (float) maxDim / (float) Math.max(width, height);
            int targetW = Math.max(1, Math.round(width * scale));
            int targetH = Math.max(1, Math.round(height * scale));
            scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] bytes = baos.toByteArray();
        if (bytes.length == 0) {
            throw new Exception("效果图 PNG 数据为空");
        }
        return bytes;
    }

    @Nullable
    private static Bitmap decodeSampledBitmapForMaxDim(
            Context context,
            @Nullable String effectImageUri,
            @Nullable String effectImagePath,
            int maxDim) {
        try {
            if (effectImageUri != null && !effectImageUri.isEmpty()) {
                Uri uri = Uri.parse(effectImageUri);
                if (uri == null) {
                    return null;
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in1 = context.getContentResolver().openInputStream(uri)) {
                    if (in1 == null) {
                        return null;
                    }
                    BitmapFactory.decodeStream(in1, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return null;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim, maxDim);
                try (InputStream in2 = context.getContentResolver().openInputStream(uri)) {
                    if (in2 == null) {
                        return null;
                    }
                    return BitmapFactory.decodeStream(in2, null, opts);
                }
            }

            if (effectImagePath != null && !effectImagePath.isEmpty()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(effectImagePath, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return null;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim, maxDim);
                return BitmapFactory.decodeFile(effectImagePath, opts);
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "decodeSampledBitmapForMaxDim", e);
        }
        return null;
    }

    private static int computeInSampleSize(
            int srcWidth, int srcHeight, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            int halfHeight = srcHeight / 2;
            int halfWidth = srcWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static byte[] updateVarConfigXmlFromStream(
            InputStream xmlStream, String author, String name, String description) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception ignored) {
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlStream);
        Element root = doc.getDocumentElement();
        if (root != null) {
            String ver = root.getAttribute("version");
            if (ver == null || ver.trim().isEmpty()) {
                root.setAttribute("version", "1");
            }
            String a = author != null ? author : "未知";
            String n = name != null ? name : "未知";
            String desc =
                    (description != null && !description.trim().isEmpty()) ? description.trim() : "无描述";
            root.setAttribute("author", a);
            root.setAttribute("name", n);
            root.setAttribute("description", desc);
        }
        return transformVarConfigDomToXmlBytes(doc);
    }

    private static byte[] createVarConfigXmlBytes(String author, String name, String description)
            throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception ignored) {
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        Element root = doc.createElement("WidgetConfig");
        String a = author != null ? author : "未知";
        String n = name != null ? name : "未知";
        String desc =
                (description != null && !description.trim().isEmpty()) ? description.trim() : "无描述";
        root.setAttribute("version", "1");
        root.setAttribute("author", a);
        root.setAttribute("name", n);
        root.setAttribute("description", desc);
        doc.appendChild(root);
        return transformVarConfigDomToXmlBytes(doc);
    }

    /** 输出带 XML 声明的 var_config，与系统主题包习惯一致。 */
    private static byte[] transformVarConfigDomToXmlBytes(Document doc) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
