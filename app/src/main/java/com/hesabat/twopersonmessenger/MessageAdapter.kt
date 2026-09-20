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
import java.util.Locale
import java.util.TimeZone

class MessageAdapter(
    private val myId: Int,
    private val onLong: (Message) -> Unit,
    private val onCall: (Boolean) -> Unit
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    val items = mutableListOf<Message>()

    class VH(
        val b: ItemMessageBinding
    ) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): VH {
        return VH(
            ItemMessageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int {
        return items.size
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int
    ) {

        val message = items[position]

        val isMissedCall =
            message.type == "call_missed_audio" ||
            message.type == "call_missed_video"

        val isMine =
            message.sender_id == myId

        /*
         * CAVABSIZ ZƏNG
         */
        if (isMissedCall) {

            val isVideo =
                message.type == "call_missed_video"

            holder.b.text.text =
                if (isVideo) {
                    "▣  Cavabsız video zəng\nGeri zəng etmək üçün toxun"
                } else {
                    "☎  Cavabsız səsli zəng\nGeri zəng etmək üçün toxun"
                }

            holder.b.text.setTextColor(
                Color.parseColor("#F15C6D")
            )

            holder.b.meta.text =
                formatPhoneTime(
                    message.created_at
                )

            holder.b.meta.setTextColor(
                Color.parseColor("#F15C6D")
            )

            holder.b.bubble.background =
                GradientDrawable().apply {

                    cornerRadius = 18f

                    setColor(
                        Color.parseColor(
                            "#202C33"
                        )
                    )

                    setStroke(
                        1,
                        Color.parseColor(
                            "#5A2930"
                        )
                    )
                }

            val params =
                holder.b.bubble.layoutParams
                    as LinearLayout.LayoutParams

            params.gravity =
                if (isMine) {
                    Gravity.END
                } else {
                    Gravity.START
                }

            holder.b.bubble.layoutParams =
                params

            /*
             * Cavabsız zəngin üstünə basanda
             * geri zəng edilir.
             */
            holder.b.bubble.setOnClickListener {

                onCall(isVideo)
            }

            holder.b.bubble.setOnLongClickListener {
                false
            }

            return
        }

        /*
         * ADİ MESAJ
         */
        holder.b.text.text =
            message.text ?: ""

        holder.b.text.setTextColor(
            Color.parseColor("#E9EDEF")
        )

        /*
         * WhatsApp tipli status:
         *
         * ✓   = göndərilib
         * ✓✓  = qarşı telefona çatıb
         * mavi ✓✓ = oxunub
         */
        val tick =
            if (!isMine) {

                ""

            } else {

                when {

                    message.read_at != null -> {
                        "  ✓✓"
                    }

                    message.delivered_at != null -> {
                        "  ✓✓"
                    }

                    else -> {
                        "  ✓"
                    }
                }
            }

        holder.b.meta.text =
            formatPhoneTime(
                message.created_at
            ) + tick

        /*
         * Oxunmuş mesajın iki quşu mavi.
         */
        holder.b.meta.setTextColor(

            if (
                isMine &&
                message.read_at != null
            ) {

                Color.parseColor(
                    "#53BDEB"
                )

            } else {

                Color.parseColor(
                    "#8696A0"
                )
            }
        )

        /*
         * Öz mesajımız sağda və yaşıl.
         * Qarşı tərəfin mesajı solda.
         */
        holder.b.bubble.background =
            GradientDrawable().apply {

                cornerRadius = 16f

                setColor(
                    Color.parseColor(
                        if (isMine) {
                            "#005C4B"
                        } else {
                            "#202C33"
                        }
                    )
                )
            }

        val params =
            holder.b.bubble.layoutParams
                as LinearLayout.LayoutParams

        params.gravity =
            if (isMine) {
                Gravity.END
            } else {
                Gravity.START
            }

        holder.b.bubble.layoutParams =
            params

        holder.b.bubble.setOnClickListener(
            null
        )

        holder.b.bubble.setOnLongClickListener {

            onLong(message)

            true
        }
    }

    /*
     * Serverdən gələn vaxtı telefonun
     * lokal saatına çevirir.
     */
    private fun formatPhoneTime(
        raw: String?
    ): String {

        if (raw.isNullOrBlank()) {
            return ""
        }

        return runCatching {

            val parser =
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
                )

            /*
             * Server vaxtı Moskva timezone-dadır.
             */
            parser.timeZone =
                TimeZone.getTimeZone(
                    "Europe/Moscow"
                )

            val date =
                parser.parse(raw)
                    ?: return@runCatching raw

            /*
             * Burada Android telefonunun
             * öz timezone-u avtomatik istifadə olunur.
             */
            val formatter =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                )

            formatter.timeZone =
                TimeZone.getDefault()

            formatter.format(date)

        }.getOrElse {

            raw.takeLast(8)
                .take(5)
        }
    }
}
