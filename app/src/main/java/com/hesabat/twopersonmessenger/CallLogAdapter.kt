package com.hesabat.twopersonmessenger
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hesabat.twopersonmessenger.databinding.ItemCallLogBinding
import java.text.SimpleDateFormat
import java.util.*
class CallLogAdapter(private val items:List<CallLogItem>,private val redial:(Boolean)->Unit):RecyclerView.Adapter<CallLogAdapter.VH>(){
 class VH(val b:ItemCallLogBinding):RecyclerView.ViewHolder(b.root)
 override fun onCreateViewHolder(p:ViewGroup,v:Int)=VH(ItemCallLogBinding.inflate(LayoutInflater.from(p.context),p,false))
 override fun getItemCount()=items.size
 override fun onBindViewHolder(h:VH,p:Int){val x=items[p];h.b.icon.text=if(x.type=="video")"▣" else "☎";h.b.title.text=if(x.type=="video")"Video zəng" else "Səsli zəng";val missed=x.status=="missed";val arrow=if(x.direction=="incoming")"↙" else "↗";val status=when{x.status=="missed"->"Cavabsız";x.status=="rejected"->"Rədd edildi";x.durationSec>0->formatDuration(x.durationSec);else->"Cavab verilmədi"};h.b.detail.text="$arrow $status";h.b.detail.setTextColor(Color.parseColor(if(missed)"#F15C6D" else "#8696A0"));h.b.time.text=SimpleDateFormat("dd.MM.yyyy  HH:mm",Locale.getDefault()).format(Date(x.startedAt));h.b.callAgain.setOnClickListener{redial(x.type=="video")};h.b.root.setOnClickListener{redial(x.type=="video")}}
 private fun formatDuration(s:Long):String=if(s<60)"${s} san" else "%d:%02d".format(s/60,s%60)
}
