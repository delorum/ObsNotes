import ru.obsnotes.InlineCrypto;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stdin-only synthetic interoperability bridge; never reads real secrets or saved passwords. */
public class CheckInlineCrypto {
  public static void main(String[] args) {
    Scanner input=new Scanner(System.in,StandardCharsets.UTF_8);
    char[] password=new String(Base64.getDecoder().decode(input.nextLine()),StandardCharsets.UTF_8).toCharArray();
    String payload=new String(Base64.getDecoder().decode(input.nextLine()),StandardCharsets.UTF_8);
    try {
      String result=args[0].equals("encrypt")?InlineCrypto.encrypt(payload,password):InlineCrypto.decrypt(payload,password);
      System.out.println(Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8)));
    } finally {Arrays.fill(password,'\0');}
  }
}
