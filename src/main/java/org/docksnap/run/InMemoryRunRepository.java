package org.docksnap.run;

import org.docksnap.domain.Run;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRunRepository implements RunRepository {
    private final Map<String, Run> map = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();

    @Override
    public synchronized void save(Run run) {
        map.put(run.id(), run);
        order.addFirst(run.id());
        while (order.size() > 50) map.remove(order.removeLast());
    }

    @Override public Run find(String id) { return map.get(id); }

    @Override
    public synchronized List<Run> list() {
        List<Run> out = new ArrayList<>();
        for (String id : order) {
            Run r = map.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    @Override public void update(Run run) { map.put(run.id(), run); }
}
