package com.mineagent.engine.cognition;

import com.mineagent.engine.exploration.EnvironmentFingerprint;
import com.mineagent.engine.exploration.MechanismKnowledgeBase;
import com.mineagent.engine.planning.PlanGraph;
import com.mineagent.engine.world.SemanticWorldModel;
import java.util.*;

/** Owner-scoped dependency jobs with expiring resource leases and observation-backed completion. */
public final class TeamWorkBoard {
    public enum Status { OPEN, LEASED, VERIFIED, CANCELLED }
    public record Job(String id, UUID author, String objective, String resource, List<String> dependencies,
                      List<PlanGraph.GoalCondition> acceptance, Status status, UUID worker,
                      long claimedTick, long leaseUntil, long updatedTick) {
        public Job { dependencies=List.copyOf(dependencies);acceptance=List.copyOf(acceptance); }
    }
    public record Result(boolean accepted, String message, Job job) {}
    public record Hint(UUID source, MechanismKnowledgeBase.Rule rule, long tick) {}
    private static final Map<UUID,Group> GROUPS=new LinkedHashMap<>();
    private TeamWorkBoard() {}

    public static synchronized Result post(UUID owner,UUID author,String id,String objective,String resource,
            List<String> dependencies,List<PlanGraph.GoalCondition> acceptance,long tick) {
        if(owner==null||author==null||id==null||!id.matches("[a-zA-Z0-9_-]{1,64}")||objective==null||objective.isBlank()
                ||objective.length()>512||resource==null||resource.isBlank()||resource.length()>256
                ||dependencies==null||dependencies.size()>12||acceptance==null||acceptance.isEmpty()||acceptance.size()>12
                ||acceptance.stream().anyMatch(Objects::isNull)) return new Result(false,"Invalid bounded team job",null);
        var group=group(owner,tick);expire(group,tick);
        if(group.jobs.containsKey(id))return new Result(false,"Job ID already exists; preserve its contract",group.jobs.get(id));
        if(dependencies.contains(id)||dependencies.stream().anyMatch(d->!group.jobs.containsKey(d)))
            return new Result(false,"Dependencies must already exist; cycles and unknown parents are rejected",null);
        // Retire finished leaves first, preserving every referenced contract and requested predecessor.
        while(group.jobs.size()>=64) {
            var referenced=new HashSet<String>(dependencies);
            group.jobs.values().forEach(j->referenced.addAll(j.dependencies()));
            var victim=group.jobs.values().stream().filter(j->j.status()==Status.VERIFIED&&!referenced.contains(j.id()))
                    .min(Comparator.comparingLong(Job::updatedTick)).orElse(null);
            if(victim==null)return new Result(false,"Team job budget exhausted; finish active work first",null);
            group.jobs.remove(victim.id());
        }
        var job=new Job(id,author,objective,resource.trim().toLowerCase(Locale.ROOT),dependencies,acceptance,Status.OPEN,null,0,0,tick);
        group.jobs.put(id,job);return new Result(true,"Team job posted; completion requires fresh acceptance observations",job);
    }
    public static synchronized Result claim(UUID owner,UUID worker,String id,long tick) {
        if(owner==null||worker==null)return new Result(false,"Missing team identity",null);
        var group=GROUPS.get(owner);if(group==null)return new Result(false,"Unknown owner-scoped job",null);
        expire(group,tick);var job=group.jobs.get(id);
        if(job==null||job.status()!=Status.OPEN)return new Result(false,"Job is not open",job);
        if(job.dependencies().stream().anyMatch(d->group.jobs.get(d).status()!=Status.VERIFIED))
            return new Result(false,"Verify predecessor jobs first",job);
        if(group.jobs.values().stream().anyMatch(j->j.status()==Status.LEASED
                && (j.worker().equals(worker)||j.resource().equals(job.resource()))))
            return new Result(false,"Worker or public resource already leased",job);
        var claimed=new Job(job.id(),job.author(),job.objective(),job.resource(),job.dependencies(),job.acceptance(),Status.LEASED,
                worker,tick,saturated(tick,200),tick);
        group.jobs.put(id,claimed);return new Result(true,"Job claimed for 200 ticks; active execution renews the lease",claimed);
    }
    public static synchronized Result complete(UUID owner,UUID worker,String id,SemanticWorldModel world,long tick) {
        if(world==null)return new Result(false,"Current world observations are required",null);
        var group=GROUPS.get(owner);if(group==null)return new Result(false,"Unknown owner-scoped job",null);
        expire(group,tick);var job=group.jobs.get(id);
        if(job==null||job.status()!=Status.LEASED||!Objects.equals(worker,job.worker()))return new Result(false,"Only the current worker can complete its lease",job);
        for(var c:job.acceptance()) {
            var fact=world.find(c.subject(),c.predicate(),tick);
            if(fact.isEmpty()||fact.get().observedTick()<job.claimedTick()||!c.matches(world,tick))
                return new Result(false,"Observe current acceptance condition: "+c.describe(),job);
        }
        var done=new Job(job.id(),job.author(),job.objective(),job.resource(),job.dependencies(),job.acceptance(),Status.VERIFIED,worker,job.claimedTick(),0,tick);
        group.jobs.put(id,done);return new Result(true,"Team job verified from fresh world observations",done);
    }
    public static synchronized void heartbeat(UUID owner,UUID worker,long tick,boolean executing) {
        var group=GROUPS.get(owner);if(group==null)return;expire(group,tick);
        if(!executing)return;
        for(var job:List.copyOf(group.jobs.values()))if(job.status()==Status.LEASED&&worker.equals(job.worker()))
            group.jobs.put(job.id(),new Job(job.id(),job.author(),job.objective(),job.resource(),job.dependencies(),job.acceptance(),job.status(),worker,job.claimedTick(),saturated(tick,200),tick));
    }
    public static synchronized List<Job> jobs(UUID owner,long tick) {
        var group=GROUPS.get(owner);if(group==null)return List.of();expire(group,tick);return List.copyOf(group.jobs.values());
    }
    public static synchronized String resourceBlocker(UUID owner,UUID member,String resource,long tick) {
        var group=GROUPS.get(owner);if(group==null)return null;expire(group,tick);
        return group.jobs.values().stream().filter(j->j.status()==Status.LEASED
                &&j.resource().equals(resource.toLowerCase(Locale.ROOT))&&!j.worker().equals(member))
                .map(j->"Public resource is leased to a teammate for job "+j.id()).findFirst().orElse(null);
    }
    public static synchronized boolean share(UUID owner,UUID member,MechanismKnowledgeBase.Rule rule,EnvironmentFingerprint current,long tick) {
        if(owner==null||member==null||rule==null||!rule.reusable(current))return false;
        var group=group(owner,tick);expire(group,tick);
        String id=member+":"+rule.id();group.hints.put(id,new Hint(member,rule,tick));
        while(group.hints.size()>64)group.hints.remove(group.hints.keySet().iterator().next());
        return true;
    }
    public static synchronized List<Hint> hints(UUID owner,UUID self,EnvironmentFingerprint current,String query,long tick) {
        var group=GROUPS.get(owner);if(group==null)return List.of();expire(group,tick);
        return group.hints.values().stream().filter(h->!h.source().equals(self)&&h.rule().fingerprint().compatible(current))
                .filter(h->query==null||query.isBlank()||com.mineagent.engine.memory.TextSimilarity.score(query,h.rule().subject()+" "+h.rule().hypothesis())>=0.2)
                .sorted(Comparator.comparing(h->h.rule().id())).limit(4).toList();
    }
    public static synchronized void withdraw(UUID owner,UUID source,String ruleId) {
        var group=GROUPS.get(owner);if(group!=null)group.hints.remove(source+":"+ruleId);
    }
    public static synchronized Optional<Hint> hint(UUID owner,UUID self,EnvironmentFingerprint current,String ruleId,long tick) {
        var group=GROUPS.get(owner);if(group==null)return Optional.empty();expire(group,tick);
        return group.hints.values().stream().filter(h->!h.source().equals(self)&&h.rule().id().equals(ruleId)
                &&h.rule().fingerprint().compatible(current)).findFirst();
    }
    public static synchronized void remove(UUID owner,UUID member) {
        var group=GROUPS.get(owner);if(group==null)return;
        group.hints.values().removeIf(h->h.source().equals(member));
        for(var job:List.copyOf(group.jobs.values()))if(job.status()==Status.LEASED&&member.equals(job.worker()))
            group.jobs.put(job.id(),new Job(job.id(),job.author(),job.objective(),job.resource(),job.dependencies(),job.acceptance(),Status.OPEN,null,0,0,job.updatedTick()));
    }
    public static synchronized void clearAll() { GROUPS.clear(); }
    private static Group group(UUID owner,long tick) {
        GROUPS.entrySet().removeIf(e->tick<e.getValue().lastTick||tick-e.getValue().lastTick>24_000);
        if(!GROUPS.containsKey(owner)&&GROUPS.size()>=256)GROUPS.remove(GROUPS.keySet().iterator().next());
        var group=GROUPS.computeIfAbsent(owner,k->new Group());group.lastTick=tick;return group;
    }
    private static void expire(Group group,long tick) {
        for(var job:List.copyOf(group.jobs.values()))if(job.status()==Status.LEASED&&(tick<job.claimedTick()||tick>job.leaseUntil()))
            group.jobs.put(job.id(),new Job(job.id(),job.author(),job.objective(),job.resource(),job.dependencies(),job.acceptance(),Status.OPEN,null,0,0,tick));
        group.hints.values().removeIf(h->tick<h.tick()||tick-h.tick()>24_000);group.lastTick=tick;
    }
    private static long saturated(long a,long b) { return a>Long.MAX_VALUE-b?Long.MAX_VALUE:Math.max(0,a+b); }
    private static final class Group {
        final Map<String,Job> jobs=new LinkedHashMap<>();final Map<String,Hint> hints=new LinkedHashMap<>();long lastTick;
    }
}
