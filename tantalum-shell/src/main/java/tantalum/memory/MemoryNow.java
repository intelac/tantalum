package tantalum.memory;

import java.time.Clock;
import java.util.Optional;
import tantalum.shell.plugin.ContextContributor;
import tantalum.value.id.Principal;

/** 工作记忆进上下文的一段（memory-final-plan 第 8 节：工作记忆每轮进上下文的可变段，按行比较变化）。 */
public final class MemoryNow implements ContextContributor {

    private final MemoryStores stores;
    private final Clock clock;

    public MemoryNow(MemoryStores stores, Clock clock) {
        this.stores = stores;
        this.clock = clock;
    }

    @Override
    public String plugin() {
        return MemoryTools.PLUGIN;
    }

    @Override
    public java.util.Set<String> sectionNames() {
        return java.util.Set.of(NowText.TAG);
    }

    @Override
    public Optional<Contribution> contribute(Principal user, Situation situation) {
        return situation.offRecord() ? Optional.of(new Contribution(null, NowText.TAG, null, true)) : contribute(user);
    }

    @Override
    public Optional<Contribution> contribute(Principal user) {
        String text = NowText.render(stores.working(user), stores.of(user), clock.instant());
        return Optional.of(new Contribution(null, NowText.TAG, text, true));
    }
}
