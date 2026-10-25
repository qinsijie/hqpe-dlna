package com.github.qinsijie.hqpedlna

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView

class AppListAdapter(
    context: Context,
    private val appList: List<AppInfo>
) : ArrayAdapter<AppInfo>(context, 0, appList) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val item = appList[position]

        val ivIcon = view.findViewById<ImageView>(R.id.iv_app_icon)
        val tvName = view.findViewById<TextView>(R.id.tv_app_name)
        val tvPackage = view.findViewById<TextView>(R.id.tv_package_name)

        tvName.text = item.appName
        tvPackage.text = "${item.packageName} (UID: ${item.uid})"
        if (item.icon != null) {
            ivIcon.setImageDrawable(item.icon)
        } else {
            ivIcon.setImageResource(R.drawable.ic_launcher)
        }

        return view
    }
}
