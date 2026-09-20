V6.6 - native SIGABRT (libjingle_peerconnection_so.so) fix
- Bütün PeerConnection/SDP observer callback-ləri try/catch içindədir. Native WebRTC Java-ya JNI ilə
  callback edəndə Java exception atılsa proses abort() ilə ölür.
- peer.receivers / getTransceivers() artıq çağırılmır (əvvəlki wrapper-ları dispose edib exception yaradırdı).
- Remote ICE candidate-lər remote SDP tətbiq olunana qədər buferlənir.
- Təkrar offer/answer və təkrar poll siqnalları ignor edilir.
- dispose sırası düzəldildi (track -> source -> factory -> ADM -> EGL), renderer-lər əvvəl release olunur.
- MODIFY_AUDIO_SETTINGS icazəsi əlavə edildi, CallActivity portrait + configChanges (zəng zamanı recreate olmur).
- WebRTC yalnız zəng açılanda init olunur.
- Xəta olarsa logcat-da TPM-RTC tag-ı ilə səbəb görünür.

V6.7 - ACCESS_NETWORK_STATE + ACCESS_WIFI_STATE icazələri əlavə edildi. WebRTC NetworkMonitor bu icazə olmadan SecurityException atır, native tərəf isə abort() edir.
