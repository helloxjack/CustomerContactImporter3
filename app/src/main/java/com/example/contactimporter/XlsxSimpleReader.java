package com.example.contactimporter;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 轻量级 xlsx 读取器：只读取第一个工作表的 A、B 两列。
 * 不依赖 Apache POI，避免安卓端体积过大。
 */
public class XlsxSimpleReader {
    public static class RowData {
        public final String name;
        public final String phone;

        public RowData(String name, String phone) {
            this.name = name == null ? "" : name.trim();
            this.phone = phone == null ? "" : phone.trim();
        }
    }

    public static List<RowData> readFirstSheetAB(InputStream in) throws Exception {
        Map<String, byte[]> entries = unzip(in);
        List<String> sharedStrings = parseSharedStrings(entries.get("xl/sharedStrings.xml"));

        byte[] sheetBytes = entries.get("xl/worksheets/sheet1.xml");
        if (sheetBytes == null) {
            for (String key : entries.keySet()) {
                if (key.startsWith("xl/worksheets/sheet") && key.endsWith(".xml")) {
                    sheetBytes = entries.get(key);
                    break;
                }
            }
        }
        if (sheetBytes == null) throw new IllegalArgumentException("未找到 xlsx 工作表，请确认文件不是损坏文件");

        return parseSheetAB(sheetBytes, sharedStrings);
    }

    private static Map<String, byte[]> unzip(InputStream in) throws Exception {
        Map<String, byte[]> map = new HashMap<>();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        byte[] buffer = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int len;
                while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                map.put(entry.getName(), out.toByteArray());
            }
            zis.closeEntry();
        }
        zis.close();
        return map;
    }

    private static List<String> parseSharedStrings(byte[] bytes) throws Exception {
        List<String> result = new ArrayList<>();
        if (bytes == null) return result;

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");

        boolean inSi = false;
        boolean inT = false;
        StringBuilder current = new StringBuilder();

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("si".equals(name)) {
                    inSi = true;
                    current.setLength(0);
                } else if (inSi && "t".equals(name)) {
                    inT = true;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (inSi && inT) current.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("t".equals(name)) {
                    inT = false;
                } else if ("si".equals(name)) {
                    result.add(current.toString());
                    inSi = false;
                }
            }
            event = parser.next();
        }
        return result;
    }

    private static List<RowData> parseSheetAB(byte[] bytes, List<String> sharedStrings) throws Exception {
        List<RowData> rows = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");

        boolean inRow = false;
        boolean inCell = false;
        boolean inV = false;
        boolean inInlineT = false;
        int rowNumber = -1;
        String cellRef = "";
        String cellType = "";
        StringBuilder cellText = new StringBuilder();
        Map<String, String> currentRow = new HashMap<>();

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("row".equals(tag)) {
                    inRow = true;
                    currentRow.clear();
                    rowNumber = parseInt(parser.getAttributeValue(null, "r"), -1);
                } else if (inRow && "c".equals(tag)) {
                    inCell = true;
                    cellRef = parser.getAttributeValue(null, "r");
                    cellType = parser.getAttributeValue(null, "t");
                    cellText.setLength(0);
                } else if (inCell && "v".equals(tag)) {
                    inV = true;
                } else if (inCell && "t".equals(tag)) {
                    inInlineT = true;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (inCell && (inV || inInlineT)) cellText.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("v".equals(tag)) {
                    inV = false;
                } else if ("t".equals(tag)) {
                    inInlineT = false;
                } else if ("c".equals(tag)) {
                    String col = columnName(cellRef);
                    if ("A".equals(col) || "B".equals(col)) {
                        currentRow.put(col, decodeCell(cellText.toString(), cellType, sharedStrings));
                    }
                    inCell = false;
                } else if ("row".equals(tag)) {
                    // 第 1 行视为表头，从第 2 行开始读取。
                    if (rowNumber != 1) {
                        String a = currentRow.get("A");
                        String b = currentRow.get("B");
                        if ((a != null && !a.trim().isEmpty()) || (b != null && !b.trim().isEmpty())) {
                            rows.add(new RowData(a, b));
                        }
                    }
                    inRow = false;
                }
            }
            event = parser.next();
        }
        return rows;
    }

    private static String decodeCell(String raw, String type, List<String> sharedStrings) {
        if (raw == null) return "";
        raw = raw.trim();
        if ("s".equals(type)) {
            int idx = parseInt(raw, -1);
            if (idx >= 0 && idx < sharedStrings.size()) return sharedStrings.get(idx);
            return "";
        }
        if ("inlineStr".equals(type) || "str".equals(type)) return raw;
        return normalizeNumeric(raw);
    }

    private static String normalizeNumeric(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        try {
            if (s.contains("E") || s.contains("e") || s.contains(".")) {
                BigDecimal bd = new BigDecimal(s);
                s = bd.toPlainString();
                if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static String columnName(String ref) {
        if (ref == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (Character.isLetter(ch)) sb.append(Character.toUpperCase(ch));
            else break;
        }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
