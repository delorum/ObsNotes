// Synthetic parser microbenchmark; run after ./gradlew :core:classes:
// javac -cp core/build/classes/java/main -d /tmp/obsnotes-bench tools/ParseBenchmark.java
// java -Xmx256m -cp /tmp/obsnotes-bench:core/build/classes/java/main ParseBenchmark
import java.util.*;
import ru.obsnotes.Markdown;

public class ParseBenchmark {
  static volatile long result;

  public static void main(String[] args) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 120; i++) {
      body.append("## Section").append(i).append("\n\n");
      for (int j = 0; j < 8; j++)
        body.append("Entry ").append(i).append('/').append(j).append("\nsecond line\n\n");
      body.append("```dataviewjs\ngenerated();\n```\n\n");
    }
    String value = body.toString();
    for (int i = 0; i < 40; i++) result += new Markdown(value).blocks.size();
    double[] elapsed = new double[5];
    for (int trial = 0; trial < 5; trial++) {
      long start = System.nanoTime();
      for (int i = 0; i < 60; i++) result += new Markdown(value).blocks.size();
      elapsed[trial] = (System.nanoTime() - start) / 60e6;
    }
    Arrays.sort(elapsed);
    System.out.println(
        "bytes="
            + value.length()
            + ", sections=120, blocks=960; median ms/parse="
            + elapsed[2]
            + "; batches="
            + Arrays.toString(elapsed));
  }
}
