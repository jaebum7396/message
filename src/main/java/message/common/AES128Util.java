package message.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.context.annotation.Configuration;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;

@Configuration
@Slf4j
public class AES128Util {
    private String ips;
    private Key keySpec;
    private String encryptKey = "ENCRYPTKEYCD_AES";
    public AES128Util() {
        try {
            byte[] keyBytes = new byte[16];
            byte[] b = encryptKey.getBytes("UTF-8");
            System.arraycopy(b, 0, keyBytes, 0, keyBytes.length);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            this.ips = encryptKey.substring(0, 16);
            this.keySpec = keySpec;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public String encrypt(String str) {
        try {
            log.info("AES128 pre_encrypt : "+str);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec,
                    new IvParameterSpec(ips.getBytes()));

            byte[] encrypted = cipher.doFinal(str.getBytes("UTF-8"));
            String StrEncrypt = new String(Base64.encodeBase64(encrypted));

            log.info("AES128 aft_encrypt : "+StrEncrypt);

            return StrEncrypt;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    public String decrypt(String str) {
        try {
            log.info("AES128 pre_decrypt : "+str);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new IvParameterSpec(ips.getBytes("UTF-8")));

            byte[] byteStr = Base64.decodeBase64(str.getBytes());
            String StrDecrypt = new String(cipher.doFinal(byteStr), "UTF-8");

            log.info("AES128 aft_decrypt : "+StrDecrypt);

            return StrDecrypt;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
