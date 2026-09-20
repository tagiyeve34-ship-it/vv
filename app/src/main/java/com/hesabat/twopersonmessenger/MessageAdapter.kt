package com.hesabat.twopersonmessenger
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.hesabat.twopersonmessenger.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.*
class MessageAdapter(private val myId:Int,private val onLong:(Message)->Unit,private val onCall:(Boolean)->Unit):RecyclerView.Adapter<MessageAdapter.VH>(){
 val items=mutableListOf<Message>();class VH(val b:ItemMessageBinding):RecyclerView.ViewHolder(b.root)
 override fun onCreateViewHolder(p:ViewGroup,v:Int)=VH(ItemMessageBinding.inflate(LayoutInflater.from(p.context),p,false));override fun getItemCount()=items.size
 override fun onBindViewHolder(h:VH,pos:Int){val m=items[pos];val call=m.type=="call_missed_audio"||m.type=="call_missed_video";val mine=m.sender_id==myId
  if(call){h.b.text.text=if(m.type.endsWith("video"))"▣  Cavabsız video zəng\nGeri zəng etmək üçün toxun" else "☎  Cavabsız səsli zəng\nGeri zəng etmək üçün toxun";h.b.text.setTextColor(Color.parseColor("#F15C6D"));h.b.meta.text=m.created_at.orEmpty();h.b.meta.setTextColor(Color.parseColor("#F15C6D"));h.b.bubble.background=GradientDrawable().apply{cornerRadius=18f;setColor(Color.parseColor("#202C33"));setStroke(1,Color.parseColor("#5A2930"))};(h.b.bubble.layoutParams as LinearLayout.LayoutParams).apply{gravity=Gravity.START;h.b.bubble.layoutParams=this};h.b.bubble.setOnClickListener{onCall(m.type.endsWith("video"))};h.b.bubble.setOnLongClickListener{false};return}
  h.b.text.text=m.text?:"";h.b.text.setTextColor(Color.parseColor("#E9EDEF"));val tick=if(!mine)"" else when{m.read_at!=null->"  ✓✓";m.delivered_at!=null->"  ✓✓";else->"  ✓"};h.b.meta.text=formatPhoneTime(m.created_at)+tick;h.b.meta.setTextColor(Color.parseColor(if(mine&&m.read_at!=null)"#53BDEB" else "#8696A0"));h.b.bubble.background=GradientDrawable().apply{cornerRadius=16f;setColor(Color.parseColor(if(mine)"#005C4B" else "#202C33"))};(h.b.bubble.layoutParams as LinearLayout.LayoutParams).apply{gravity=if(mine)Gravity.END else Gravity.START;h.b.bubble.layoutParams=this};h.b.bubble.setOnClickListener(null);h.b.bubble.setOnLongClickListener{onLong(m);true}}
 private fun formatPhoneTime(raw:String?):String{if(raw.isNullOrBlank())return"";return runCatching{val p=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);p.timeZone=TimeZone.getTimeZone("Europe/Moscow");val d=p.parse(raw)!!;SimpleDateFormat("HH:mm",Locale.getDefault()).format(d)}.getOrElse{raw.takeLast(8).take(5)}}
}
