import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(TimeExecutionExtension.class)
public class DensityTests {

    @BeforeAll
    static void init() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.out.println("---------------------------");
    }

    @AfterAll
    static void after() {
        System.out.println("---------------------------");
    }

    @Test
    @DisplayName("Thread Sleep")
    void test() throws InterruptedException {
        Thread.sleep(1000);
    }
}
