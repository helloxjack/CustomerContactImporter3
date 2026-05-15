package com.example.contactimporter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements ContactAdapter.Listener {
    private static final int REQ_OPEN_XLSX = 1001;
    private static final int REQ_CREATE_CSV = 1002;
    private static final int REQ_CONTACT_PERMISSION = 2001;

    private static final String GROUP_ALL = "全部分组";
    private static final String PREFS_NAME = "contact_importer_prefs";
    private static final String KEY_DEFAULT_BATCH = "default_batch_count";

    private ContactDbHelper db;
    private ContactAdapter adapter;
    private TextView tvStats;
    private EditText etSearch;
    private Spinner spFilter;
    private Spinner spGroup;
    private Button btnDefaultBatch;
    private String currentKeyword = "";
    private int currentFilterStatus = -1;
    private String currentGroupLabel = GROUP_ALL;

    private int pendingBatchCount = 0;
    private String pendingBatchGroup = null;
    private boolean pendingRangeImport = false;
    private String pendingRangeGroup = null;
    private int pendingRangeStart = 0;
    private int pendingRangeEnd = 0;
    private long pendingSingleContactId = -1;

    private final String[] filterLabels = {"全部", "未导入", "已导入", "导入失败", "号码异常", "重复号码"};
    private final int[] filterStatuses = {-1, Contact.STATUS_PENDING, Contact.STATUS_IMPORTED, Contact.STATUS_FAILED, Contact.STATUS_INVALID, Contact.STATUS_DUPLICATE};

    private interface GroupCallback {
        void onGroupReady(String groupName);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new ContactDbHelper(this);
        adapter = new ContactAdapter(this);

        tvStats = findViewById(R.id.tvStats);
        etSearch = findViewById(R.id.etSearch);
        spFilter = findViewById(R.id.spFilter);
        spGroup = findViewById(R.id.spGroup);
        ListView listView = findViewById(R.id.listContacts);
        listView.setAdapter(adapter);

        setupGroupSpinner(GROUP_ALL);
        setupFilter();
        setupButtons();
        setupSearch();
        refresh();

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void setupButtons() {
        Button btnImportExcel = findViewById(R.id.btnImportExcel);
        Button btnAdd = findViewById(R.id.btnAdd);
        Button btnExport = findViewById(R.id.btnExport);
        Button btnReset = findViewById(R.id.btnReset);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnCleanImported = findViewById(R.id.btnCleanImported);
        Button btnHelp = findViewById(R.id.btnHelp);
        Button btnGroupAdd = findViewById(R.id.btnGroupAdd);
        Button btnGroupRename = findViewById(R.id.btnGroupRename);
        Button btnGroupDelete = findViewById(R.id.btnGroupDelete);
        Button btnBatch5 = findViewById(R.id.btnBatch5);
        Button btnBatch10 = findViewById(R.id.btnBatch10);
        Button btnBatch20 = findViewById(R.id.btnBatch20);
        Button btnBatch50 = findViewById(R.id.btnBatch50);
        Button btnBatchCustom = findViewById(R.id.btnBatchCustom);
        Button btnRangeImport = findViewById(R.id.btnRangeImport);
        Button btnDefaultSettings = findViewById(R.id.btnDefaultSettings);
        btnDefaultBatch = findViewById(R.id.btnDefaultBatch);
        updateDefaultBatchButtonText();

        btnImportExcel.setOnClickListener(v -> openXlsxPicker());
        btnAdd.setOnClickListener(v -> showEditDialog(null));
        btnExport.setOnClickListener(v -> createCsvFile());
        btnHelp.setOnClickListener(v -> showHelpDialog());
        btnGroupAdd.setOnClickListener(v -> showCreateGroupDialog(null));
        btnGroupRename.setOnClickListener(v -> showRenameGroupDialog());
        btnGroupDelete.setOnClickListener(v -> deleteCurrentGroup());

        btnReset.setOnClickListener(v -> {
            String group = getCurrentGroupForQuery();
            String target = group == null ? "全部分组" : group;
            confirm("确认将【" + target + "】中已导入/导入失败的数据重置为未导入？", () -> {
                db.resetImportedToPending(group);
                refresh();
                toast("已重置");
            });
        });

        btnCleanImported.setOnClickListener(v -> {
            String group = getCurrentGroupForQuery();
            String target = group == null ? "全部分组" : group;
            confirm("确认清理【" + target + "】中状态为“已导入”的软件库记录？\n此操作不会删除手机通讯录里的联系人。", () -> {
                int deleted = db.deleteImportedContacts(group);
                refresh();
                toast("已清理已导入记录：" + deleted + " 条");
            });
        });

        btnClear.setOnClickListener(v -> confirm("确认清空软件内所有联系人数据？\n此操作不会删除手机通讯录里已写入的联系人，也不会删除分组名称。", () -> {
            db.clearAllContacts();
            refresh();
            toast("已清空联系人库");
        }));

        btnDefaultBatch.setOnClickListener(v -> importNextBatch(getDefaultBatchCount(), getCurrentGroupForQuery()));
        btnDefaultSettings.setOnClickListener(v -> showDefaultBatchDialog());
        btnBatch5.setOnClickListener(v -> importNextBatch(5, getCurrentGroupForQuery()));
        btnBatch10.setOnClickListener(v -> importNextBatch(10, getCurrentGroupForQuery()));
        btnBatch20.setOnClickListener(v -> importNextBatch(20, getCurrentGroupForQuery()));
        btnBatch50.setOnClickListener(v -> importNextBatch(50, getCurrentGroupForQuery()));
        btnBatchCustom.setOnClickListener(v -> showCustomBatchDialog());
        btnRangeImport.setOnClickListener(v -> showRangeImportDialog());
    }

    private int getDefaultBatchCount() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return sp.getInt(KEY_DEFAULT_BATCH, 20);
    }

    private void setDefaultBatchCount(int count) {
        if (count <= 0) count = 20;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_DEFAULT_BATCH, count).apply();
        updateDefaultBatchButtonText();
        refreshStats();
    }

    private void updateDefaultBatchButtonText() {
        if (btnDefaultBatch != null) btnDefaultBatch.setText("默认导入" + getDefaultBatchCount() + "人");
    }

    private void showDefaultBatchDialog() {
        final String[] labels = {"5 人", "10 人", "20 人", "50 人", "100 人", "自定义"};
        final int[] values = {5, 10, 20, 50, 100, -1};
        new AlertDialog.Builder(this)
                .setTitle("设置默认导入人数")
                .setItems(labels, (dialog, which) -> {
                    if (values[which] > 0) {
                        setDefaultBatchCount(values[which]);
                        toast("默认导入人数已设为 " + values[which] + " 人");
                    } else {
                        showCustomDefaultBatchDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomDefaultBatchDialog() {
        EditText et = new EditText(this);
        et.setHint("输入默认人数，例如 30");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int pad = dp(18);
        et.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(this)
                .setTitle("自定义默认导入人数")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    int count = parseInt(et.getText().toString(), 0);
                    if (count <= 0) {
                        toast("请输入正确人数");
                        return;
                    }
                    setDefaultBatchCount(count);
                    toast("默认导入人数已设为 " + count + " 人");
                })
                .show();
    }

    private void showRenameGroupDialog() {
        String oldGroup = getCurrentGroupForQuery();
        if (oldGroup == null) {
            toast("请先选择要重命名的具体分组");
            return;
        }
        if (ContactDbHelper.DEFAULT_GROUP.equals(oldGroup)) {
            toast("默认分组不能重命名");
            return;
        }
        EditText etName = new EditText(this);
        etName.setText(oldGroup);
        etName.setSelectAllOnFocus(true);
        etName.setSingleLine(true);
        int pad = dp(18);
        etName.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(this)
                .setTitle("重命名分组")
                .setView(etName)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newName = ContactDbHelper.cleanGroupName(etName.getText().toString());
                    if (newName.isEmpty()) {
                        toast("分组名称不能为空");
                        return;
                    }
                    if (ContactDbHelper.DEFAULT_GROUP.equals(newName)) {
                        toast("不能改成默认分组名称");
                        return;
                    }
                    boolean ok = db.renameGroup(oldGroup, newName);
                    if (ok) {
                        setupGroupSpinner(newName);
                        refresh();
                        toast("分组已重命名为：" + newName);
                    } else {
                        toast("重命名失败");
                    }
                })
                .show();
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("使用说明")
                .setMessage("1. Excel 表格默认读取第一个工作表，A列为姓名/公司名，B列为手机号，从第2行开始读取。\n\n" +
                        "2. 导入 Excel 前先选择分组，联系人会在该分组内自动从 1 开始排序。\n\n" +
                        "3. “默认导入”会按当前分组内未导入联系人序号从小到大导入；也可以用 5/10/20/50 或自定义人数。\n\n" +
                        "4. “序号区间”适合按 1-30、31-60 这样的节奏分批写入通讯录。\n\n" +
                        "5. 软件会先检查手机通讯录是否已存在同手机号，避免重复写入。\n\n" +
                        "6. 数据仅保存在本机；清空软件库不会删除手机通讯录中的联系人。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentKeyword = s == null ? "" : s.toString();
                refreshList();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void setupFilter() {
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, filterLabels);
        spAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFilter.setAdapter(spAdapter);
        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentFilterStatus = filterStatuses[position];
                refreshList();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupGroupSpinner(String preferred) {
        List<String> values = new ArrayList<>();
        values.add(GROUP_ALL);
        values.addAll(db.getGroupNames());
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        spAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGroup.setAdapter(spAdapter);

        int selected = 0;
        if (preferred != null) {
            for (int i = 0; i < values.size(); i++) {
                if (preferred.equals(values.get(i))) {
                    selected = i;
                    break;
                }
            }
        }
        currentGroupLabel = values.get(selected);
        spGroup.setSelection(selected, false);
        spGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object item = parent.getItemAtPosition(position);
                currentGroupLabel = item == null ? GROUP_ALL : item.toString();
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private String getCurrentGroupForQuery() {
        if (currentGroupLabel == null || currentGroupLabel.trim().isEmpty() || GROUP_ALL.equals(currentGroupLabel)) return null;
        return currentGroupLabel;
    }

    private String getCurrentGroupOrDefault() {
        String g = getCurrentGroupForQuery();
        return g == null ? ContactDbHelper.DEFAULT_GROUP : g;
    }

    private void refresh() {
        refreshStats();
        refreshList();
    }

    private void refreshStats() {
        String group = getCurrentGroupForQuery();
        ContactDbHelper.Stats s = db.getStats(group);
        String title = group == null ? GROUP_ALL : group;
        tvStats.setText("当前分组：" + title + "    默认导入：" + getDefaultBatchCount() + " 人" +
                "\n总数：" + s.total +
                "  ｜ 未导入：" + s.pending +
                "  ｜ 已导入：" + s.imported +
                "\n号码异常：" + s.invalid +
                "  ｜ 重复号码：" + s.duplicate +
                "  ｜ 导入失败：" + s.failed +
                "\n提示：导入通讯录前会检查手机通讯录同手机号；区间导入需先选择具体分组。");
    }

    private void refreshList() {
        if (adapter == null || db == null) return;
        adapter.setData(db.getContacts(currentKeyword, currentFilterStatus, getCurrentGroupForQuery()));
    }

    private void openXlsxPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try {
            startActivityForResult(intent, REQ_OPEN_XLSX);
        } catch (Exception e) {
            Intent alt = new Intent(Intent.ACTION_GET_CONTENT);
            alt.addCategory(Intent.CATEGORY_OPENABLE);
            alt.setType("*/*");
            startActivityForResult(Intent.createChooser(alt, "选择 .xlsx 文件"), REQ_OPEN_XLSX);
        }
    }

    private void createCsvFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "联系人导入记录_" + System.currentTimeMillis() + ".csv");
        startActivityForResult(intent, REQ_CREATE_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQ_OPEN_XLSX) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            chooseGroupThenImport(uri);
        } else if (requestCode == REQ_CREATE_CSV) {
            exportCsv(uri);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) uri = (Uri) extra;
        }
        if (uri != null) chooseGroupThenImport(uri);
    }

    private void chooseGroupThenImport(Uri uri) {
        List<String> groups = db.getGroupNames();
        List<String> items = new ArrayList<>(groups);
        items.add("新建分组...");
        new AlertDialog.Builder(this)
                .setTitle("选择导入分组")
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    String chosen = items.get(which);
                    if ("新建分组...".equals(chosen)) {
                        showCreateGroupDialog(groupName -> importXlsxToGroup(uri, groupName));
                    } else {
                        importXlsxToGroup(uri, chosen);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importXlsxToGroup(Uri uri, String groupName) {
        groupName = ContactDbHelper.cleanGroupName(groupName);
        if (groupName.isEmpty()) groupName = ContactDbHelper.DEFAULT_GROUP;
        db.createGroup(groupName);

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("无法读取文件");
                return;
            }
            String sourceFile = getDisplayName(uri);
            List<XlsxSimpleReader.RowData> rows = XlsxSimpleReader.readFirstSheetAB(in);
            int total = 0, inserted = 0, invalid = 0, duplicate = 0;
            Set<String> seenInThisFile = new HashSet<>();

            for (XlsxSimpleReader.RowData row : rows) {
                total++;
                String name = row.name == null ? "" : row.name.trim();
                String originalPhone = row.phone == null ? "" : row.phone.trim();
                String normalized = normalizePhone(originalPhone);

                if (name.isEmpty() && !normalized.isEmpty()) name = normalized;
                if (name.isEmpty() && normalized.isEmpty()) continue;

                Contact c = new Contact();
                c.name = name;
                c.phone = originalPhone;
                c.normalizedPhone = normalized;
                c.sourceFile = sourceFile;
                c.remark = "";
                c.importedAt = "";
                c.groupName = groupName;
                c.groupSeq = 0; // 由数据库按该分组自动分配下一个序号。

                if (!isValidChinaMobile(normalized)) {
                    c.status = Contact.STATUS_INVALID;
                    invalid++;
                } else if (seenInThisFile.contains(normalized) || db.phoneExists(normalized)) {
                    c.status = Contact.STATUS_DUPLICATE;
                    duplicate++;
                } else {
                    c.status = Contact.STATUS_PENDING;
                    inserted++;
                    seenInThisFile.add(normalized);
                }
                db.insert(c);
            }
            db.resequenceGroup(groupName);
            setupGroupSpinner(groupName);
            refresh();
            new AlertDialog.Builder(this)
                    .setTitle("Excel 导入完成")
                    .setMessage("目标分组：" + groupName +
                            "\n读取行数：" + total +
                            "\n新增待导入：" + inserted +
                            "\n号码异常：" + invalid +
                            "\n重复号码：" + duplicate +
                            "\n\n该分组内联系人已按序号从 1 开始排列。")
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
            new AlertDialog.Builder(this)
                    .setTitle("导入失败")
                    .setMessage("请确认文件是 .xlsx，且 A 列为姓名、B 列为手机号。\n\n错误信息：" + e.getMessage())
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    private void importNextBatch(int count, String groupName) {
        if (!hasContactPermission()) {
            pendingBatchCount = count;
            pendingBatchGroup = groupName;
            pendingSingleContactId = -1;
            pendingRangeImport = false;
            requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS}, REQ_CONTACT_PERMISSION);
            return;
        }
        List<Contact> list = db.getNextPending(count, groupName);
        if (list.isEmpty()) {
            toast("没有可导入的未导入联系人");
            return;
        }
        String target = groupName == null ? GROUP_ALL : groupName;
        confirm("确认按【" + target + "】的当前顺序导入 " + list.size() + " 人到手机通讯录？", () -> runBatchImport(list));
    }

    private void showCustomBatchDialog() {
        EditText etCount = new EditText(this);
        etCount.setHint("输入人数，例如 30");
        etCount.setSingleLine(true);
        etCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int pad = dp(18);
        etCount.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(this)
                .setTitle("自定义人数导入")
                .setView(etCount)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    int count = parseInt(etCount.getText().toString(), 0);
                    if (count <= 0) {
                        toast("请输入正确人数");
                        return;
                    }
                    importNextBatch(count, getCurrentGroupForQuery());
                })
                .show();
    }

    private void showRangeImportDialog() {
        String group = getCurrentGroupForQuery();
        if (group == null) {
            toast("请先在分组下拉框中选择具体分组，再按序号区间导入");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etStart = new EditText(this);
        etStart.setHint("起始序号，例如 1");
        etStart.setSingleLine(true);
        etStart.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText etEnd = new EditText(this);
        etEnd.setHint("结束序号，例如 50");
        etEnd.setSingleLine(true);
        etEnd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(etStart);
        box.addView(etEnd);

        new AlertDialog.Builder(this)
                .setTitle("按序号区间导入：" + group)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (dialog, which) -> {
                    int start = parseInt(etStart.getText().toString(), 0);
                    int end = parseInt(etEnd.getText().toString(), 0);
                    if (start <= 0 || end <= 0 || start > end) {
                        toast("请输入正确的序号区间");
                        return;
                    }
                    importSeqRange(group, start, end);
                })
                .show();
    }

    private void importSeqRange(String groupName, int startSeq, int endSeq) {
        if (!hasContactPermission()) {
            pendingRangeImport = true;
            pendingRangeGroup = groupName;
            pendingRangeStart = startSeq;
            pendingRangeEnd = endSeq;
            pendingBatchCount = 0;
            pendingSingleContactId = -1;
            requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS}, REQ_CONTACT_PERMISSION);
            return;
        }
        List<Contact> list = db.getPendingBySeqRange(groupName, startSeq, endSeq);
        if (list.isEmpty()) {
            toast("该序号区间内没有未导入联系人");
            return;
        }
        confirm("确认导入【" + groupName + "】中序号 " + startSeq + " - " + endSeq + " 的未导入联系人？\n实际可导入：" + list.size() + " 人", () -> runBatchImport(list));
    }

    private void runBatchImport(List<Contact> list) {
        int success = 0, failed = 0;
        for (Contact c : list) {
            try {
                boolean ok = writeToSystemContacts(c);
                if (ok) {
                    db.updateStatus(c.id, Contact.STATUS_IMPORTED, ContactDbHelper.now());
                    success++;
                } else {
                    db.updateStatus(c.id, Contact.STATUS_FAILED, "");
                    failed++;
                }
            } catch (Exception e) {
                e.printStackTrace();
                db.updateStatus(c.id, Contact.STATUS_FAILED, "");
                failed++;
            }
        }
        refresh();
        new AlertDialog.Builder(this)
                .setTitle("导入通讯录完成")
                .setMessage("成功：" + success + " 人\n失败：" + failed + " 人")
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    public void onImport(Contact c) {
        if (c == null) return;
        if (c.status != Contact.STATUS_PENDING) {
            toast("当前状态不可导入：" + Contact.statusText(c.status));
            return;
        }
        if (!hasContactPermission()) {
            pendingSingleContactId = c.id;
            pendingBatchCount = 0;
            pendingRangeImport = false;
            requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS}, REQ_CONTACT_PERMISSION);
            return;
        }
        List<Contact> one = new ArrayList<>();
        one.add(c);
        runBatchImport(one);
    }

    @Override
    public void onDial(Contact c) {
        String phone = c == null ? "" : safe(c.normalizedPhone == null || c.normalizedPhone.isEmpty() ? c.phone : c.normalizedPhone);
        if (phone.isEmpty()) {
            toast("手机号为空");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
        startActivity(intent);
    }

    @Override
    public void onSms(Contact c) {
        String phone = c == null ? "" : safe(c.normalizedPhone == null || c.normalizedPhone.isEmpty() ? c.phone : c.normalizedPhone);
        if (phone.isEmpty()) {
            toast("手机号为空");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
        startActivity(intent);
    }

    @Override
    public void onEdit(Contact c) {
        showEditDialog(c);
    }

    @Override
    public void onDelete(Contact c) {
        confirm("确认删除该联系人？\n" + c.groupName + " #" + c.groupSeq + "\n" + c.name + "\n" + c.normalizedPhone, () -> {
            db.delete(c.id);
            refresh();
        });
    }

    private void showEditDialog(Contact old) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView tvGroup = new TextView(this);
        tvGroup.setText("所属分组");
        Spinner spEditGroup = new Spinner(this);
        List<String> groups = db.getGroupNames();
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, groups);
        groupAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEditGroup.setAdapter(groupAdapter);

        String defaultGroup = old == null ? getCurrentGroupOrDefault() : old.groupName;
        int selected = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).equals(defaultGroup)) { selected = i; break; }
        }
        spEditGroup.setSelection(selected);

        EditText etName = new EditText(this);
        etName.setHint("姓名 / 公司名");
        etName.setSingleLine(true);
        EditText etPhone = new EditText(this);
        etPhone.setHint("手机号");
        etPhone.setSingleLine(true);
        EditText etSeq = new EditText(this);
        etSeq.setHint("组内序号，可不填");
        etSeq.setSingleLine(true);
        etSeq.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText etRemark = new EditText(this);
        etRemark.setHint("备注，可不填");
        etRemark.setSingleLine(false);
        etRemark.setMinLines(2);

        if (old != null) {
            etName.setText(old.name);
            etPhone.setText(old.normalizedPhone == null || old.normalizedPhone.isEmpty() ? old.phone : old.normalizedPhone);
            etSeq.setText(String.valueOf(old.groupSeq));
            etRemark.setText(old.remark);
        } else {
            etSeq.setText(String.valueOf(db.getNextSeq(defaultGroup)));
        }

        box.addView(tvGroup);
        box.addView(spEditGroup);
        box.addView(etName);
        box.addView(etPhone);
        box.addView(etSeq);
        box.addView(etRemark);

        final String oldGroup = old == null ? defaultGroup : old.groupName;
        new AlertDialog.Builder(this)
                .setTitle(old == null ? "新增联系人" : "编辑联系人")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();
                    String normalized = normalizePhone(phone);
                    String remark = etRemark.getText().toString().trim();
                    String group = spEditGroup.getSelectedItem() == null ? ContactDbHelper.DEFAULT_GROUP : spEditGroup.getSelectedItem().toString();
                    int seq = parseInt(etSeq.getText().toString(), 0);
                    if (name.isEmpty()) {
                        toast("姓名不能为空");
                        return;
                    }

                    Contact c = old == null ? new Contact() : old;
                    c.name = name;
                    c.phone = phone;
                    c.normalizedPhone = normalized;
                    c.remark = remark;
                    c.groupName = group;
                    if (old == null) {
                        c.sourceFile = "手动新增";
                        c.importedAt = "";
                    }

                    boolean groupChanged = old != null && oldGroup != null && !oldGroup.equals(group);
                    if (groupChanged) c.groupSeq = db.getNextSeq(group);
                    else c.groupSeq = seq <= 0 ? db.getNextSeq(group) : seq;

                    long exceptId = old == null ? -1 : old.id;
                    if (!isValidChinaMobile(normalized)) {
                        c.status = Contact.STATUS_INVALID;
                    } else if (db.phoneExistsExcept(normalized, exceptId)) {
                        c.status = Contact.STATUS_DUPLICATE;
                    } else if (old == null || old.status == Contact.STATUS_INVALID || old.status == Contact.STATUS_DUPLICATE || old.status == Contact.STATUS_FAILED) {
                        c.status = Contact.STATUS_PENDING;
                    }

                    if (old == null) db.insert(c); else db.update(c);
                    if (old != null && groupChanged) db.resequenceGroup(oldGroup);
                    db.resequenceGroup(group);
                    setupGroupSpinner(group);
                    refresh();
                })
                .show();
    }

    private void showCreateGroupDialog(GroupCallback callback) {
        EditText etName = new EditText(this);
        etName.setHint("输入分组名称，例如 松江客户");
        etName.setSingleLine(true);
        int pad = dp(18);
        etName.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(this)
                .setTitle("新建分组")
                .setView(etName)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = ContactDbHelper.cleanGroupName(etName.getText().toString());
                    if (name.isEmpty()) {
                        toast("分组名称不能为空");
                        return;
                    }
                    db.createGroup(name);
                    setupGroupSpinner(name);
                    refresh();
                    toast("已创建分组：" + name);
                    if (callback != null) callback.onGroupReady(name);
                })
                .show();
    }

    private void deleteCurrentGroup() {
        String group = getCurrentGroupForQuery();
        if (group == null) {
            toast("请先选择要删除的具体分组");
            return;
        }
        if (ContactDbHelper.DEFAULT_GROUP.equals(group)) {
            toast("默认分组不能删除");
            return;
        }
        confirm("确认删除分组【" + group + "】？\n会同步删除该分组内的软件库联系人；不会删除已经写入手机通讯录的联系人。", () -> {
            db.deleteGroupWithContacts(group);
            setupGroupSpinner(GROUP_ALL);
            refresh();
            toast("已删除分组：" + group);
        });
    }

    private boolean writeToSystemContacts(Contact c) throws Exception {
        if (c == null || c.name == null || c.name.trim().isEmpty() || !isValidChinaMobile(c.normalizedPhone)) return false;
        if (contactExistsInSystem(c.normalizedPhone)) return true;
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        int rawContactInsertIndex = ops.size();

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, c.name)
                .build());

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, c.normalizedPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());

        String note = "客户通讯录导入助手：分组=" + safe(c.groupName) + "，序号=" + c.groupSeq;
        if (c.remark != null && !c.remark.trim().isEmpty()) note += "，备注=" + c.remark.trim();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                .build());

        getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        return true;
    }

    private boolean contactExistsInSystem(String phone) {
        if (phone == null || phone.trim().isEmpty()) return false;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone));
        String[] projection = new String[]{ContactsContract.PhoneLookup._ID};
        try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    private void exportCsv(Uri uri) {
        List<Contact> list = db.getContacts(currentKeyword, currentFilterStatus, getCurrentGroupForQuery());
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"))) {
            writer.write('\ufeff');
            writer.write("分组,序号,姓名,手机号,状态,来源文件,导入时间,备注\n");
            for (Contact c : list) {
                writer.write(csv(c.groupName)); writer.write(',');
                writer.write(csv(String.valueOf(c.groupSeq))); writer.write(',');
                writer.write(csv(c.name)); writer.write(',');
                writer.write(csv(c.normalizedPhone == null || c.normalizedPhone.isEmpty() ? c.phone : c.normalizedPhone)); writer.write(',');
                writer.write(csv(Contact.statusText(c.status))); writer.write(',');
                writer.write(csv(c.sourceFile)); writer.write(',');
                writer.write(csv(c.importedAt)); writer.write(',');
                writer.write(csv(c.remark)); writer.write('\n');
            }
            toast("导出完成");
        } catch (Exception e) {
            e.printStackTrace();
            toast("导出失败：" + e.getMessage());
        }
    }

    private String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private boolean hasContactPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACT_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingSingleContactId > 0) {
                    Contact c = db.getById(pendingSingleContactId);
                    pendingSingleContactId = -1;
                    if (c != null) {
                        List<Contact> one = new ArrayList<>();
                        one.add(c);
                        runBatchImport(one);
                    }
                } else if (pendingRangeImport) {
                    pendingRangeImport = false;
                    importSeqRange(pendingRangeGroup, pendingRangeStart, pendingRangeEnd);
                } else {
                    int count = pendingBatchCount <= 0 ? 1 : pendingBatchCount;
                    String group = pendingBatchGroup;
                    pendingBatchCount = 0;
                    pendingBatchGroup = null;
                    importNextBatch(count, group);
                }
            } else {
                toast("未获得通讯录写入权限，无法导入手机通讯录");
            }
        }
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("+86")) s = s.substring(3);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) digits.append(ch);
        }
        String d = digits.toString();
        if (d.length() == 13 && d.startsWith("86")) d = d.substring(2);
        if (d.length() > 11 && d.startsWith("0")) {
            d = d.substring(d.length() - 11);
        }
        return d;
    }

    private boolean isValidChinaMobile(String phone) {
        return phone != null && phone.matches("1\\d{10}");
    }

    private String getDisplayName(Uri uri) {
        String result = "Excel文件";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void confirm(String message, Runnable yesAction) {
        new AlertDialog.Builder(this)
                .setTitle("确认操作")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> yesAction.run())
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (Exception e) { return def; }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
