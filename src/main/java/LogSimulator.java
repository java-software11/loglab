import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/*
 * ================================================================
 *  LOG SIMULATOR — Linux komutlarını PRATİK YAPMAK için veri üretici
 * ================================================================
 *
 * AMAÇ:
 *   grep, awk, sed, pipe, cron, ssh, chmod/chown, systemctl gibi
 *   komutları BOŞ komut satırında değil, GERÇEK ve GERÇEKÇİ log
 *   dosyaları üzerinde denemen için bu program 2 dosya üretir:
 *
 *   1) access.log  -> bir web sunucusunun erişim kaydı gibi
 *                      (IP, tarih, HTTP metodu, path, status kodu, boyut)
 *   2) app.log     -> bir Java uygulamasının kendi logu gibi
 *                      (seviye: INFO/WARN/ERROR, mesaj, bazen stack trace)
 *
 * NASIL ÇALIŞTIRILIR (WSL Ubuntu içinde):
 *   javac LogSimulator.java
 *   java LogSimulator
 *
 * Bu komut çalışınca aynı klasörde access.log ve app.log oluşur.
 * Ardından grep/awk/sed ile bu dosyalar üzerinde alıştırma yaparız.
 *
 * BONUS "SÜREKLİ MOD":
 *   java LogSimulator --loop
 *   ile program arka planda sürekli yeni log satırı ekler — bu sayede
 *   systemctl ile "servis" haline getirip, journalctl/tail -f ile
 *   CANLI log izlemeyi de deneyebiliriz.
 */
public class LogSimulator {

    // [SABİTLER] — gerçekçi veri üretmek için örnek değerler
    static final String[] IP_HAVUZU = {
            "192.168.1.10", "192.168.1.11", "10.0.0.5",
            "203.0.113.7", "203.0.113.7", "203.0.113.7", // bu IP kasten sık tekrar ediyor
            "198.51.100.23", "198.51.100.99"
    };

    static final String[] HTTP_METODLARI = {"GET", "GET", "GET", "POST", "PUT", "DELETE"};

    static final String[] PATHLER = {
            "/api/urunler", "/api/siparisler", "/api/musteriler",
            "/health", "/login", "/api/urunler/42"
    };

    // Status kodları -> bilerek bazı 500/404 hatalar eklendi ki grep/awk ile arayacak bir şey olsun
    static final int[] STATUS_KODLARI = {200, 200, 200, 200, 201, 301, 404, 500, 500, 403};

    // [DÜZELTME] Eskiden seviye (INFO/WARN/ERROR) ve mesaj birbirinden
    // BAĞIMSIZ rastgele seçiliyordu -> "ERROR - Kullanıcı girişi başarılı"
    // gibi mantıksız satırlar çıkabiliyordu. Artık her seviyenin KENDİ
    // mesaj havuzu var; önce seviye seçiliyor, mesaj o seviyeye uygun
    // havuzdan geliyor. Böylece ERROR satırları hep gerçek bir hata
    // mesajı taşıyor.
    static final String[] INFO_MESAJLARI = {
            "Kullanıcı girişi başarılı",
            "Sipariş oluşturuldu",
            "Önbellek (cache) yenilendi",
            "Yeni kullanıcı kaydı tamamlandı",
            "Rapor başarıyla oluşturuldu"
    };

    static final String[] WARN_MESAJLARI = {
            "Veritabanı bağlantı havuzu %80 dolulukta",
            "Yanıt süresi beklenenden uzun sürdü",
            "Disk kullanımı %75'i geçti",
            "Üçüncü parti servisten yavaş yanıt alındı"
    };

    static final String[] ERROR_MESAJLARI = {
            "Ödeme servisi zaman aşımına uğradı",
            "Beklenmeyen hata oluştu",
            "Veritabanına bağlanılamadı",
            "Stok güncellenirken hata oluştu",
            "Kimlik doğrulama servisi yanıt vermedi"
    };

    static final Random rastgele = new Random();

    public static void main(String[] args) throws Exception {

        boolean surekliMod = args.length > 0 && args[0].equals("--loop");

        Path accessLogYolu = Paths.get("access.log");
        Path appLogYolu = Paths.get("app.log");

        if (surekliMod) {
            // [SÜREKLİ MOD] — systemctl ile servis yapıp journalctl/tail -f
            // denemek için: program kapanmadan sürekli yeni satır ekliyor.
            System.out.println("Sürekli mod başladı. Durdurmak için Ctrl+C (ya da systemctl stop).");
            while (true) {
                appendAccessLogSatiri(accessLogYolu);
                appendAppLogSatiri(appLogYolu);
                Thread.sleep(1000); // her 1 saniyede bir yeni log satırı
            }
        } else {
            // [TEK SEFERLİK MOD] — grep/awk/sed pratiği için sabit boyutlu dosya üret
            System.out.println("500 satırlık access.log ve app.log üretiliyor...");
            for (int i = 0; i < 500; i++) {
                appendAccessLogSatiri(accessLogYolu);
                appendAppLogSatiri(appLogYolu);
            }
            System.out.println("Bitti: " + accessLogYolu.toAbsolutePath());
            System.out.println("Bitti: " + appLogYolu.toAbsolutePath());
        }
    }

    // ============================================================
    // access.log formatı (basitleştirilmiş "Apache Combined Log" tarzı):
    //   IP - - [tarih] "METOD path HTTP/1.1" status boyut
    // Bu format gerçek dünyada nginx/apache loglarının temelidir.
    // awk ile sütun sütun ($1 = IP, $6 = metod, $9 = status gibi) okunur.
    // ============================================================
    static void appendAccessLogSatiri(Path yol) throws IOException {
        String ip = rastgele(IP_HAVUZU);
        String tarih = simdikiZamanDamgasi();
        String metod = rastgele(HTTP_METODLARI);
        String path = rastgele(PATHLER);
        int status = rastgele(STATUS_KODLARI);
        int boyut = 200 + rastgele.nextInt(5000);

        // [TEXT BLOCK KULLANMADIK ÇÜNKÜ] tek satırlık, formatlı log satırı
        // için String.format zaten yeterli ve daha okunaklı.
        String satir = String.format(
                "%s - - [%s] \"%s %s HTTP/1.1\" %d %d%n",
                ip, tarih, metod, path, status, boyut
        );

        // [FILES.WRITE + APPEND] — dosya yoksa oluştur, varsa sonuna ekle.
        // sed ile bu dosyayı değiştirirken, önce -i OLMADAN deneyeceğiz.
        Files.writeString(yol, satir, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ============================================================
    // app.log formatı: bir Spring/Java uygulamasının tipik log satırı:
    //   [tarih] SEVIYE - mesaj
    // ERROR olduğunda bazen sahte bir "stack trace" da ekleniyor,
    // böylece grep -A (sonraki satırları da göster) pratiği yapılabilir.
    // ============================================================
    static void appendAppLogSatiri(Path yol) throws IOException {
        String tarih = simdikiZamanDamgasi();

        // [DÜZELTME] Önce seviyeyi seç (INFO ihtimali daha yüksek olsun diye
        // 0-100 arası bir zar atıp aralıklara bölüyoruz: gerçek uygulamalarda
        // da INFO loglar WARN/ERROR'dan çok daha sık olur).
        int zar = rastgele.nextInt(100);
        String seviye;
        String mesaj;
        if (zar < 70) {
            seviye = "INFO";
            mesaj = rastgele(INFO_MESAJLARI);
        } else if (zar < 90) {
            seviye = "WARN";
            mesaj = rastgele(WARN_MESAJLARI);
        } else {
            seviye = "ERROR";
            mesaj = rastgele(ERROR_MESAJLARI);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] %s - %s%n", tarih, seviye, mesaj));

        // ERROR ise sahte stack trace ekle -> grep -A 3 "ERROR" ile
        // "hatanın altındaki detayları da göster" pratiği için.
        if (seviye.equals("ERROR")) {
            sb.append("    at com.ornek.servis.OdemeServisi.gonder(OdemeServisi.java:42)\n");
            sb.append("    at com.ornek.controller.SiparisController.olustur(SiparisController.java:17)\n");
            sb.append("    at java.base/java.lang.Thread.run(Thread.java:840)\n");
        }

        Files.writeString(yol, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String simdikiZamanDamgasi() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss");
        return LocalDateTime.now().format(fmt);
    }

    static String rastgele(String[] havuz) {
        return havuz[rastgele.nextInt(havuz.length)];
    }

    static int rastgele(int[] havuz) {
        return havuz[rastgele.nextInt(havuz.length)];
    }
}
