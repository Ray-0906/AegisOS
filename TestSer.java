import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class TestSer {
    public static void main(String[] args) throws Exception {
        Object[] arr = new Object[] {
            "jobId", new byte[0], "artifactId", "className", null, new byte[0], new byte[0], "localJarPath"
        };
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(arr);
            oos.flush();
            System.out.println("Success! length: " + bos.toByteArray().length);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
