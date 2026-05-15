package com.example.contactimporter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class ContactAdapter extends BaseAdapter {
    public interface Listener {
        void onImport(Contact c);
        void onDial(Contact c);
        void onSms(Contact c);
        void onEdit(Contact c);
        void onDelete(Contact c);
    }

    private List<Contact> data = new ArrayList<>();
    private final Listener listener;

    public ContactAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setData(List<Contact> data) {
        this.data = data == null ? new ArrayList<>() : data;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Contact getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact, parent, false);
            h = new Holder();
            h.tvName = convertView.findViewById(R.id.tvName);
            h.tvPhone = convertView.findViewById(R.id.tvPhone);
            h.tvInfo = convertView.findViewById(R.id.tvInfo);
            h.tvStatusBadge = convertView.findViewById(R.id.tvStatusBadge);
            h.btnImport = convertView.findViewById(R.id.btnOneImport);
            h.btnDial = convertView.findViewById(R.id.btnDial);
            h.btnSms = convertView.findViewById(R.id.btnSms);
            h.btnEdit = convertView.findViewById(R.id.btnEdit);
            h.btnDelete = convertView.findViewById(R.id.btnDelete);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        Contact c = getItem(position);
        h.tvName.setText("#" + c.groupSeq + "  " + safe(c.name));
        String displayPhone = (c.normalizedPhone == null || c.normalizedPhone.isEmpty()) ? c.phone : c.normalizedPhone;
        h.tvPhone.setText("手机号：" + safe(displayPhone));

        String info = "分组：" + safe(c.groupName) + "｜来源：" + safe(c.sourceFile);
        if (c.importedAt != null && !c.importedAt.trim().isEmpty()) info += "｜导入：" + c.importedAt;
        if (c.remark != null && !c.remark.trim().isEmpty()) info += "｜备注：" + c.remark;
        h.tvInfo.setText(info);

        h.tvStatusBadge.setText(Contact.statusText(c.status));
        applyBadgeStyle(h.tvStatusBadge, c.status);

        h.btnImport.setEnabled(c.status == Contact.STATUS_PENDING);
        h.btnImport.setAlpha(c.status == Contact.STATUS_PENDING ? 1.0f : 0.45f);
        h.btnImport.setOnClickListener(v -> listener.onImport(c));
        h.btnDial.setOnClickListener(v -> listener.onDial(c));
        h.btnSms.setOnClickListener(v -> listener.onSms(c));
        h.btnEdit.setOnClickListener(v -> listener.onEdit(c));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(c));
        return convertView;
    }

    private void applyBadgeStyle(TextView tv, int status) {
        if (status == Contact.STATUS_IMPORTED) {
            tv.setBackgroundResource(R.drawable.bg_status_imported);
            tv.setTextColor(0xFF1F7A43);
        } else if (status == Contact.STATUS_FAILED) {
            tv.setBackgroundResource(R.drawable.bg_status_failed);
            tv.setTextColor(0xFFB45A00);
        } else if (status == Contact.STATUS_INVALID) {
            tv.setBackgroundResource(R.drawable.bg_status_invalid);
            tv.setTextColor(0xFFC94A4A);
        } else if (status == Contact.STATUS_DUPLICATE) {
            tv.setBackgroundResource(R.drawable.bg_status_duplicate);
            tv.setTextColor(0xFF6A45B8);
        } else {
            tv.setBackgroundResource(R.drawable.bg_status_pending);
            tv.setTextColor(0xFF2F6F7E);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    static class Holder {
        TextView tvName, tvPhone, tvInfo, tvStatusBadge;
        Button btnImport, btnDial, btnSms, btnEdit, btnDelete;
    }
}
