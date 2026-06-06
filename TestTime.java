public class TestTime {
    public static void main(String[] args) throws Exception {
        java.io.File f = new java.io.File("test.txt");
        f.createNewFile();
        System.out.println("Before: " + f.lastModified());
        boolean ok = f.setLastModified(System.currentTimeMillis() - 60000);
        System.out.println("Set OK: " + ok);
        System.out.println("After: " + f.lastModified());
        java.nio.file.Path p = f.toPath();
        System.out.println("NIO: " + java.nio.file.Files.getLastModifiedTime(p).toMillis());
    }
}
