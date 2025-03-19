package experimentation.remotedebug;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

@SpringBootApplication
public class RemoteDebugApplication {

	public static void main(String[] args) {
		SpringApplication.run(RemoteDebugApplication.class, args);
	}

	@Controller
    record DebugedController(SimpleAsyncTaskExecutor executor) {

		public DebugedController() {
			this(new SimpleAsyncTaskExecutor());
			executor.setVirtualThreads(true);
		}

		@GetMapping("/test")
		ResponseBodyEmitter test(){
			ResponseBodyEmitter emitter = new ResponseBodyEmitter();
			executor.execute(new RunnableEmmiter(emitter));
			return emitter;
		}
	}

	static class RunnableEmmiter implements Runnable{

		public static final Duration PAUSE_TIME = Duration.ofMillis(50);
		private final ResponseBodyEmitter emitter;
		private boolean stop = false;

        RunnableEmmiter(ResponseBodyEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
		public void run() {
			while (!stop) {
                try {
					emitter.send(LocalDateTime.now());
					emitter.send("\n");
                    Thread.sleep(PAUSE_TIME);
                } catch (InterruptedException | IOException e) {
                    stop = true;
                }
            }
			emitter.complete();
		}
	}



}
