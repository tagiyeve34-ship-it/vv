package com.hesabat.twopersonmessenger

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.hesabat.twopersonmessenger.databinding.ActivityCallsBinding

class CallsActivity:AppCompatActivity(){
 private lateinit var b:ActivityCallsBinding
 override fun onCreate(s:Bundle?){super.onCreate(s);b=ActivityCallsBinding.inflate(layoutInflater);setContentView(b.root);window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,android.view.WindowManager.LayoutParams.FLAG_SECURE);b.backBtn.setOnClickListener{finish()};b.list.layoutManager=LinearLayoutManager(this);b.list.adapter=CallLogAdapter(CallHistory.list(this)){video->startActivity(Intent(this,CallActivity::class.java).putExtra("video",video).putExtra("incoming",false))}}
}
