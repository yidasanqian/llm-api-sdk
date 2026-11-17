package io.github.yidasanqian.llm.bridge;

import io.github.yidasanqian.llm.bridge.BridgeStreamMapper;
import io.github.yidasanqian.llm.bridge.BridgeStreamSession;
import io.github.yidasanqian.llm.protocol.SseEvent;
import reactor.core.publisher.Flux;

final class BridgeStreamTestSupport {

    private BridgeStreamTestSupport() {
    }

    static Flux<SseEvent<String>> mapStream(
            BridgeStreamMapper mapper,
            Flux<SseEvent<String>> source) {
        BridgeStreamSession session = mapper.newSession();
        return source.concatMap(event -> Flux.fromIterable(session.map(event)))
                .concatWith(Flux.defer(() -> Flux.fromIterable(session.finishSuccess())));
    }
}
