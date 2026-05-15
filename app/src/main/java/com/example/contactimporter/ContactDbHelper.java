package com.example.contactimporter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ContactDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "contacts_importer.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "contacts";
    private static final String TABLE_GROUPS = "contact_groups";
    public static final String DEFAULT_GROUP = "默认分组";

    public ContactDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "phone TEXT," +
                "normalized_phone TEXT," +
                "status INTEGER NOT NULL," +
                "remark TEXT," +
                "source_file TEXT," +
                "imported_at TEXT," +
                "created_at TEXT," +
                "group_name TEXT NOT NULL DEFAULT '" + DEFAULT_GROUP + "'," +
                "group_seq INTEGER NOT NULL DEFAULT 0" +
                ")");
        createIndexes(db);
        createGroupsTable(db);
        insertGroupIfMissing(db, DEFAULT_GROUP);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createGroupsTable(db);
            insertGroupIfMissing(db, DEFAULT_GROUP);
            safeExec(db, "ALTER TABLE " + TABLE + " ADD COLUMN group_name TEXT NOT NULL DEFAULT '" + DEFAULT_GROUP + "'");
            safeExec(db, "ALTER TABLE " + TABLE + " ADD COLUMN group_seq INTEGER NOT NULL DEFAULT 0");
            createIndexes(db);
            resequenceGroup(db, DEFAULT_GROUP);
        }
    }

    private void createGroupsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUPS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE NOT NULL," +
                "created_at TEXT" +
                ")");
    }

    private void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_status ON " + TABLE + "(status)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_phone ON " + TABLE + "(normalized_phone)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_group_seq ON " + TABLE + "(group_name, group_seq)");
    }

    private void safeExec(SQLiteDatabase db, String sql) {
        try { db.execSQL(sql); } catch (Exception ignored) {}
    }

    public boolean createGroup(String name) {
        name = cleanGroupName(name);
        if (name.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        return insertGroupIfMissing(db, name) >= 0;
    }

    private long insertGroupIfMissing(SQLiteDatabase db, String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("created_at", now());
        return db.insertWithOnConflict(TABLE_GROUPS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void deleteGroupWithContacts(String groupName) {
        groupName = cleanGroupName(groupName);
        if (groupName.isEmpty() || DEFAULT_GROUP.equals(groupName)) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, "group_name=?", new String[]{groupName});
        db.delete(TABLE_GROUPS, "name=?", new String[]{groupName});
    }

    public boolean renameGroup(String oldName, String newName) {
        oldName = cleanGroupName(oldName);
        newName = cleanGroupName(newName);
        if (oldName.isEmpty() || newName.isEmpty()) return false;
        if (DEFAULT_GROUP.equals(oldName)) return false;
        if (oldName.equals(newName)) return true;
        SQLiteDatabase db = getWritableDatabase();

        boolean targetExists = false;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_GROUPS + " WHERE name=?", new String[]{newName})) {
            targetExists = c.moveToFirst() && c.getInt(0) > 0;
        }

        if (!targetExists) {
            ContentValues cvGroup = new ContentValues();
            cvGroup.put("name", newName);
            db.update(TABLE_GROUPS, cvGroup, "name=?", new String[]{oldName});
        } else {
            db.delete(TABLE_GROUPS, "name=?", new String[]{oldName});
        }

        ContentValues cvContacts = new ContentValues();
        cvContacts.put("group_name", newName);
        db.update(TABLE, cvContacts, "group_name=?", new String[]{oldName});
        insertGroupIfMissing(db, newName);
        resequenceGroup(db, newName);
        return true;
    }

    public int deleteImportedContacts(String groupName) {
        SQLiteDatabase db = getWritableDatabase();
        int deleted;
        if (groupName == null || groupName.trim().isEmpty()) {
            deleted = db.delete(TABLE, "status=?", new String[]{String.valueOf(Contact.STATUS_IMPORTED)});
            for (String g : getGroupNames()) resequenceGroup(db, g);
        } else {
            deleted = db.delete(TABLE, "group_name=? AND status=?", new String[]{groupName, String.valueOf(Contact.STATUS_IMPORTED)});
            resequenceGroup(db, groupName);
        }
        return deleted;
    }

    public List<String> getGroupNames() {
        SQLiteDatabase db = getWritableDatabase();
        createGroupsTable(db);
        insertGroupIfMissing(db, DEFAULT_GROUP);

        List<String> groups = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM " + TABLE_GROUPS + " ORDER BY CASE WHEN name=? THEN 0 ELSE 1 END, id ASC", new String[]{DEFAULT_GROUP})) {
            while (c.moveToNext()) groups.add(c.getString(0));
        }
        if (groups.isEmpty()) groups.add(DEFAULT_GROUP);
        return groups;
    }

    public long insert(Contact c) {
        SQLiteDatabase db = getWritableDatabase();
        if (c.groupName == null || c.groupName.trim().isEmpty()) c.groupName = DEFAULT_GROUP;
        insertGroupIfMissing(db, c.groupName);
        if (c.groupSeq <= 0) c.groupSeq = getNextSeq(c.groupName);
        ContentValues cv = toValues(c, true);
        return db.insert(TABLE, null, cv);
    }

    public void update(Contact c) {
        SQLiteDatabase db = getWritableDatabase();
        if (c.groupName == null || c.groupName.trim().isEmpty()) c.groupName = DEFAULT_GROUP;
        insertGroupIfMissing(db, c.groupName);
        if (c.groupSeq <= 0) c.groupSeq = getNextSeq(c.groupName);
        ContentValues cv = toValues(c, false);
        db.update(TABLE, cv, "id=?", new String[]{String.valueOf(c.id)});
    }

    public void updateStatus(long id, int status, String importedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        cv.put("imported_at", importedAt == null ? "" : importedAt);
        db.update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        Contact c = getById(id);
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
        if (c != null) resequenceGroup(c.groupName);
    }

    public void clearAllContacts() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    public void resetImportedToPending(String groupName) {
        ContentValues cv = new ContentValues();
        cv.put("status", Contact.STATUS_PENDING);
        cv.putNull("imported_at");
        SQLiteDatabase db = getWritableDatabase();
        if (groupName == null || groupName.trim().isEmpty()) {
            db.update(TABLE, cv, "status=? OR status=?", new String[]{
                    String.valueOf(Contact.STATUS_IMPORTED), String.valueOf(Contact.STATUS_FAILED)
            });
        } else {
            db.update(TABLE, cv, "group_name=? AND (status=? OR status=?)", new String[]{
                    groupName, String.valueOf(Contact.STATUS_IMPORTED), String.valueOf(Contact.STATUS_FAILED)
            });
        }
    }

    public boolean phoneExists(String normalizedPhone) {
        return phoneExistsExcept(normalizedPhone, -1);
    }

    public boolean phoneExistsExcept(String normalizedPhone, long exceptId) {
        if (normalizedPhone == null || normalizedPhone.trim().isEmpty()) return false;
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE normalized_phone=? AND status<>?";
        List<String> args = new ArrayList<>();
        args.add(normalizedPhone);
        args.add(String.valueOf(Contact.STATUS_DUPLICATE));
        if (exceptId > 0) {
            sql += " AND id<>?";
            args.add(String.valueOf(exceptId));
        }
        try (Cursor c = db.rawQuery(sql, args.toArray(new String[0]))) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    public List<Contact> getContacts(String keyword, int filterStatus, String groupName) {
        List<Contact> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();

        if (groupName != null && !groupName.trim().isEmpty()) {
            where.append(" AND group_name=?");
            args.add(groupName);
        }
        if (filterStatus >= 0) {
            where.append(" AND status=?");
            args.add(String.valueOf(filterStatus));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            where.append(" AND (name LIKE ? OR phone LIKE ? OR normalized_phone LIKE ? OR remark LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }

        String sql = "SELECT * FROM " + TABLE + " WHERE " + where + " ORDER BY group_name ASC, group_seq ASC, id ASC";
        try (Cursor c = db.rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public List<Contact> getNextPending(int limit, String groupName) {
        List<Contact> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder sql = new StringBuilder("SELECT * FROM " + TABLE + " WHERE status=?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(Contact.STATUS_PENDING));
        if (groupName != null && !groupName.trim().isEmpty()) {
            sql.append(" AND group_name=?");
            args.add(groupName);
        }
        sql.append(" ORDER BY group_name ASC, group_seq ASC, id ASC LIMIT ?");
        args.add(String.valueOf(limit));
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public List<Contact> getPendingBySeqRange(String groupName, int startSeq, int endSeq) {
        List<Contact> list = new ArrayList<>();
        if (groupName == null || groupName.trim().isEmpty()) return list;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM " + TABLE + " WHERE group_name=? AND status=? AND group_seq BETWEEN ? AND ? ORDER BY group_seq ASC, id ASC", new String[]{
                groupName, String.valueOf(Contact.STATUS_PENDING), String.valueOf(startSeq), String.valueOf(endSeq)
        })) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public int getNextSeq(String groupName) {
        SQLiteDatabase db = getReadableDatabase();
        groupName = cleanGroupName(groupName);
        if (groupName.isEmpty()) groupName = DEFAULT_GROUP;
        try (Cursor c = db.rawQuery("SELECT MAX(group_seq) FROM " + TABLE + " WHERE group_name=?", new String[]{groupName})) {
            if (c.moveToFirst()) return c.getInt(0) + 1;
        }
        return 1;
    }

    public Contact getById(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT * FROM " + TABLE + " WHERE id=?", new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        return null;
    }

    public void resequenceGroup(String groupName) {
        resequenceGroup(getWritableDatabase(), groupName);
    }

    private void resequenceGroup(SQLiteDatabase db, String groupName) {
        groupName = cleanGroupName(groupName);
        if (groupName.isEmpty()) groupName = DEFAULT_GROUP;
        List<Long> ids = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM " + TABLE + " WHERE group_name=? ORDER BY CASE WHEN group_seq<=0 THEN 999999 ELSE group_seq END ASC, id ASC", new String[]{groupName})) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        int seq = 1;
        for (Long id : ids) {
            ContentValues cv = new ContentValues();
            cv.put("group_seq", seq++);
            db.update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
        }
    }

    public Stats getStats(String groupName) {
        Stats s = new Stats();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT status, COUNT(*) FROM " + TABLE;
        String[] args = null;
        if (groupName != null && !groupName.trim().isEmpty()) {
            sql += " WHERE group_name=?";
            args = new String[]{groupName};
        }
        sql += " GROUP BY status";
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                int status = c.getInt(0);
                int count = c.getInt(1);
                s.total += count;
                if (status == Contact.STATUS_PENDING) s.pending = count;
                else if (status == Contact.STATUS_IMPORTED) s.imported = count;
                else if (status == Contact.STATUS_FAILED) s.failed = count;
                else if (status == Contact.STATUS_INVALID) s.invalid = count;
                else if (status == Contact.STATUS_DUPLICATE) s.duplicate = count;
            }
        }
        return s;
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    public static String cleanGroupName(String name) {
        return name == null ? "" : name.trim().replace("\n", " ").replace("\r", " ");
    }

    private ContentValues toValues(Contact c, boolean includeCreatedAt) {
        ContentValues cv = new ContentValues();
        cv.put("name", c.name == null ? "" : c.name);
        cv.put("phone", c.phone == null ? "" : c.phone);
        cv.put("normalized_phone", c.normalizedPhone == null ? "" : c.normalizedPhone);
        cv.put("status", c.status);
        cv.put("remark", c.remark == null ? "" : c.remark);
        cv.put("source_file", c.sourceFile == null ? "" : c.sourceFile);
        cv.put("imported_at", c.importedAt == null ? "" : c.importedAt);
        cv.put("group_name", c.groupName == null || c.groupName.trim().isEmpty() ? DEFAULT_GROUP : c.groupName.trim());
        cv.put("group_seq", c.groupSeq);
        if (includeCreatedAt) cv.put("created_at", now());
        return cv;
    }

    private Contact fromCursor(Cursor c) {
        Contact x = new Contact();
        x.id = c.getLong(c.getColumnIndexOrThrow("id"));
        x.name = c.getString(c.getColumnIndexOrThrow("name"));
        x.phone = c.getString(c.getColumnIndexOrThrow("phone"));
        x.normalizedPhone = c.getString(c.getColumnIndexOrThrow("normalized_phone"));
        x.status = c.getInt(c.getColumnIndexOrThrow("status"));
        x.remark = c.getString(c.getColumnIndexOrThrow("remark"));
        x.sourceFile = c.getString(c.getColumnIndexOrThrow("source_file"));
        x.importedAt = c.getString(c.getColumnIndexOrThrow("imported_at"));
        x.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
        x.groupName = c.getString(c.getColumnIndexOrThrow("group_name"));
        x.groupSeq = c.getInt(c.getColumnIndexOrThrow("group_seq"));
        if (x.groupName == null || x.groupName.trim().isEmpty()) x.groupName = DEFAULT_GROUP;
        return x;
    }

    public static class Stats {
        public int total;
        public int pending;
        public int imported;
        public int failed;
        public int invalid;
        public int duplicate;
    }
}
