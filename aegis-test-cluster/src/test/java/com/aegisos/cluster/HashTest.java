public class HashTest {
    public static void main(String[] args) {
        String n0 = "08659ba51384";
        String n1 = "f7b805518cc7";
        String n2 = "768b038b88cb";
        int c0=0, c1=0, c2=0;
        for(int i=0; i<15; i++) {
            String job = java.util.UUID.randomUUID().toString();
            int h0 = (job + "-" + n0).hashCode();
            int h1 = (job + "-" + n1).hashCode();
            int h2 = (job + "-" + n2).hashCode();
            if (h0 < h1 && h0 < h2) c0++;
            else if (h1 < h0 && h1 < h2) c1++;
            else c2++;
        }
        System.out.println(c0 + " " + c1 + " " + c2);
    }
}
