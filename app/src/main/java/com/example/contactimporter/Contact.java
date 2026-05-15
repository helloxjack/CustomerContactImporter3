package com.example.contactimporter;

public class Contact {
    public static final int STATUS_PENDING = 0;      // 未导入通讯录
    public static final int STATUS_IMPORTED = 1;     // 已导入通讯录
    public static final int STATUS_FAILED = 2;       // 导入失败
    public static final int STATUS_INVALID = 3;      // 号码异常
    public static final int STATUS_DUPLICATE = 4;    // 重复号码

    public long id;
    public String name;
    public String phone;
    public String normalizedPhone;
    public int status;
    public String remark;
    public String sourceFile;
    public String importedAt;
    public String createdAt;

    // v2 新增：软件内部分组与组内序号。序号用于按区间导入手机通讯录。
    public String groupName;
    public int groupSeq;

    public static String statusText(int status) {
        switch (status) {
            case STATUS_PENDING:
                return "未导入";
            case STATUS_IMPORTED:
                return "已导入";
            case STATUS_FAILED:
                return "导入失败";
            case STATUS_INVALID:
                return "号码异常";
            case STATUS_DUPLICATE:
                return "重复号码";
            default:
                return "未知";
        }
    }
}
