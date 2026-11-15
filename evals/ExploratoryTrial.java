package com.example.server.service;

import com.alibaba.fastjson2.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.server.dto.*;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.service.ai.*;
import com.example.server.service.ai.impl.*;
import com.example.server.service.context.VideoContextService;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.*;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local, explicitly invoked exploration; no component scanning, web server, MQ or schedulers. */
public class ExploratoryTrial {
    static Path output;
    static boolean live;
    static JSONObject manifest;
    static final String[] CASES = {"N01", "N02", "N03"};
    static final List<List<Long>> MEDIA = List.of(List.of(42L,43L), List.of(42L,44L), List.of(43L,44L));
    static final String[] GOALS = {
        "结合第14、15讲，整理一份复习笔记，对比数组、单链表和双向循环链表的存储与访问特点，说明为什么操作复杂度需要区分定位节点与修改指针。",
        "根据第14、16讲，整理Stack、Queue、Priority Queue的取出规则对照笔记，说明二叉堆与优先队列的关系。",
        "结合第15、16讲，整理BST与Binary Min Heap的区别：它们分别约束哪些节点的大小关系，主要支持什么操作？"
    };
    static JSONObject obj(Object... values) {
        var o=new JSONObject();
        for (int i=0;i<values.length;i+=2) o.put((String)values[i],values[i+1]);
        return o;
    }
    static void write(Path p, Object value) {
        try {
            Path temp = p.resolveSibling(p.getFileName()+".tmp");
            Files.writeString(temp, JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat));
            Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { throw new IllegalStateException("Cannot persist exploration record", e); }
    }
    // ponytail: single serial process with a lifetime file lock; parallel trials need a shared transactional budget.
    static class Guard {
        long held;
        final long limit;
        final long priorHeld;
        final Path file;
        final List<JSONObject> holds = new ArrayList<>();
        Guard(Path file, long limit, long priorHeld) {
            if (limit < 0 || priorHeld < 0 || limit > 3000000 || limit > 10000000 - priorHeld)
                throw new IllegalArgumentException("Invalid cumulative budget");
            this.file=file; this.limit=limit; this.priorHeld=priorHeld;
        }
        static long cost(String provider, long input, long out) {
            if (input<0 || out<0) throw new IllegalArgumentException("Invalid reservation");
            if (AiRateLimitService.DEEPSEEK.equals(provider)) return Math.addExact(Math.multiplyExact(input,2),Math.multiplyExact(out,8));
            if (AiRateLimitService.ALIYUN_EMBEDDING.equals(provider) && out==0) return Math.addExact(input,1)/2;
            throw new IllegalArgumentException("Unpriced provider or output");
        }
        JSONObject hold(String provider, String kind, long input, long out) {
            if (!live) throw new IllegalStateException("Dry run forbids all paid attempts");
            long fee=cost(provider,input,out);
            if (held+fee>limit) throw new StudyExecutionContext.GovernanceException("EVAL_BUDGET_EXHAUSTED","本批货币预留不足，未发送请求");
            var ctx=StudyExecutionContext.require();
            var h=obj("provider",provider,"kind",kind,"requestId",ctx.requestId,"runId",ctx.runId,"purpose",ctx.purpose,
                "reservedInput",input,"reservedOutput",out,"reservedMicroCny",fee,"createdAt",Instant.now().toString());
            holds.add(h); held+=fee; save(); return h;
        }
        void save() { write(file,obj("currency","CNY","batchLimitMicroCny",limit,"roundLimitMicroCny",10000000,
                "heldMicroCny",held,"priorHeldMicroCny",priorHeld,"roundHeldMicroCny",priorHeld+held,"refundsApplied",false,"holds",holds)); }
        static void selfCheck() {
            if (cost("deepseek",60000,10000)!=200000 || cost("aliyun-embedding",501,0)!=251) throw new AssertionError("Fee math");
            boolean previous=live; live=true;
            var g=new Guard(Path.of("not-written-by-self-check"),0,0);
            try { g.hold("deepseek","GENERATION",1,1); throw new AssertionError("Cap bypass"); }
            catch (StudyExecutionContext.GovernanceException expected) { if (g.held!=0) throw new AssertionError("Rejected hold changed budget"); }
            finally { live=previous; }
            try { new Guard(Path.of("not-written-by-self-check"),3000000,8000000); throw new AssertionError("Round cap bypass"); }
            catch (IllegalArgumentException expected) {}
            try { cost("unpriced",1,0); throw new AssertionError("Unpriced allowed"); } catch (IllegalArgumentException expected) {}
        }
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.example.server.mapper")
    @Import({StudyAccessService.class,CourseService.class,TranscriptService.class,VideoContextService.class,
        StudyTaskPreparationService.class,VideoConversationStore.class,VideoAgentRunService.class,StudyRunControl.class,
        StudyArtifactService.class,StudyAgentToolDispatcher.class,StudyAgentRuntime.class,VideoAgentWorkflowService.class,
        StudyEvidenceService.class,VideoKnowledgeIndexService.class,VideoKnowledgeRetriever.class,VideoEvidenceContextService.class,
        VideoAgentEvidenceService.class,VideoChunkSummarizer.class,AiPromptBudget.class,DeepSeekChatClient.class,
        AliyunTextEmbeddingClient.class,AiRetryExecutor.class,AiRateLimitService.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url=System.getenv("EVAL_DB_URL");
            if (url==null || !url.matches("jdbc:mysql://localhost:3307/vediomind_eval_[a-z0-9_]+\\?.*")) throw new IllegalArgumentException("Isolated database required");
            return new DriverManagerDataSource(url,"root",System.getenv("EVAL_DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource d) { return new JdbcTemplate(d); }
        @Bean PlatformTransactionManager transactions(DataSource d) { return new DataSourceTransactionManager(d); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource d) throws Exception {
            var c=new MybatisConfiguration(); c.setMapUnderscoreToCamelCase(true); c.setLogImpl(NoLoggingImpl.class);
            var f=new MybatisSqlSessionFactoryBean(); f.setDataSource(d); f.setConfiguration(c); return f.getObject();
        }
        @Bean(destroyMethod="shutdown") RedissonClient redisson(org.springframework.core.env.Environment e) {
            var c=new org.redisson.config.Config();
            c.useSingleServer().setAddress("redis://"+e.getProperty("spring.data.redis.host","localhost")+":"+e.getProperty("spring.data.redis.port","6379"));
            return Redisson.create(c);
        }
        @Bean QdrantKnowledgeIndexService qdrant(org.springframework.core.env.Environment e) {
            return new QdrantKnowledgeIndexService(e.getProperty("qdrant.host","localhost"),e.getProperty("qdrant.grpc-port",Integer.class,6334),
                e.getProperty("qdrant.collection","video_knowledge_chunks_v1"),e.getProperty("qdrant.api-key",""),3) {
                @Override public void replaceMediaIndex(List<VideoKnowledgeChunk> chunks) { throw new IllegalStateException("Read-only index"); }
                @Override public void deleteMedia(Long id) { throw new IllegalStateException("Read-only index"); }
            };
        }
        @Bean Guard guard() { return new Guard(output.resolve("budget.json"),manifest.getLongValue("batchLimitMicroCny"),manifest.getLongValue("priorHeldMicroCny")); }
        @Bean AiCallLedger ledger(JdbcTemplate j,PlatformTransactionManager tm,StudyRunControl r,StudyTaskPreparationService t,AiRetryExecutor retry,Guard guard) {
            return new AiCallLedger(j,tm,r,t,retry) {
                @Override public String reserve(String provider,String kind,String fp,long input,long out) {
                    JSONObject h=guard.hold(provider,kind,input,out);
                    String id=super.reserve(provider,kind,fp,input,out); h.put("attemptId",id); guard.save(); return id;
                }
                @Override public WireResponse exchange(String p,String k,JSONObject body,Runnable before,Instant deadline,Transport transport) {
                    var ctx=StudyExecutionContext.require();
                    if (ctx.task==null || ctx.runId==null) throw new IllegalStateException("Only prepared trial calls allowed");
                    if (!(AiRateLimitService.DEEPSEEK.equals(p) && AiPromptBudget.MODEL.equals(body.getString("model")))
                            && !(AiRateLimitService.ALIYUN_EMBEDDING.equals(p) && AliyunTextEmbeddingClient.MODEL.equals(body.getString("model"))))
                        throw new IllegalStateException("Unpriced model");
                    write(output.resolve("request-"+UUID.randomUUID()+".json"),obj("requestId",ctx.requestId,"purpose",ctx.purpose,"body",body));
                    return super.exchange(p,k,body,before,deadline,transport);
                }
            };
        }
    }

    static void export(JdbcTemplate j,String id,Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String table:List.of("video_agent_runs","video_agent_steps","study_run_events","ai_call_attempts","study_artifacts")) {
            String key=table.equals("video_agent_runs")?"id":"run_id";
            String order=table.equals("study_run_events")?"sequence_no":"created_at";
            write(directory.resolve(table+".json"),j.queryForList("SELECT * FROM "+table+" WHERE "+key+"=? ORDER BY "+order,id));
        }
        write(directory.resolve("request-ledger.json"),j.queryForList("SELECT q.* FROM study_ai_requests q JOIN video_agent_runs r ON r.request_id=q.id WHERE r.id=?",id));
    }
    public static void main(String[] args) throws Exception {
        if (args.length!=2 || !Set.of("preflight","run").contains(args[0])) throw new IllegalArgumentException("preflight|run OUTPUT_DIRECTORY");
        live=args[0].equals("run"); output=Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        manifest=JSON.parseObject(Files.readString(output.resolve("manifest.json")));
        Guard.selfCheck();
        var selectedCases = manifest.getList("caseOrder", String.class);
        if (selectedCases == null || selectedCases.isEmpty()
                || new HashSet<>(selectedCases).size() != selectedCases.size()
                || !List.of(CASES).containsAll(selectedCases))
            throw new IllegalArgumentException("Invalid frozen case order");
        if ("notes-exploration-003".equals(manifest.getString("experimentId"))
                && (!selectedCases.equals(List.of("N03")) || manifest.getLongValue("batchLimitMicroCny") > 1000000))
            throw new IllegalArgumentException("Experiment 003 permits N03 once within one CNY");
        try (var channel=java.nio.channels.FileChannel.open(output.resolve("runner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             var lock=channel.tryLock(); var app=new AnnotationConfigApplicationContext()) {
            if (lock==null || Files.exists(output.resolve("budget.json"))) throw new IllegalStateException("Already executed or locked; no automatic rerun");
            var props=new Properties();
            try (var input=Files.newInputStream(Path.of("server/src/main/resources/application.properties"))) { props.load(input); }
            app.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("local-private-settings",props));
            String ds=app.getEnvironment().getProperty("ai.deepseek.base-url");
            String em=app.getEnvironment().getProperty("ai.aliyun.embedding-base-url","https://dashscope.aliyuncs.com/api/v1");
            if (!Set.of("https://api.deepseek.com","https://api.deepseek.com/v1").contains(ds) || !"https://dashscope.aliyuncs.com/api/v1".equals(em)) throw new IllegalArgumentException("Endpoint fee verification required");
            app.register(Config.class); app.refresh();
            if (!StudyAgentSpec.snapshot().get("runtime").equals(manifest.getString("runtimeVersion")))
                throw new IllegalStateException("Compiled runtime differs from frozen manifest");
            var j=app.getBean(JdbcTemplate.class); var courses=app.getBean(CourseService.class);
            var indexes=app.getBean(VideoKnowledgeIndexService.class); var tasks=app.getBean(StudyTaskPreparationService.class);
            var contexts=app.getBean(VideoContextService.class);
            write(output.resolve("resources.json"),obj("resources",StudyAgentSpec.snapshot(),"tools",StudyAgentSpec.tools(),"systemPrompt",StudyAgentSpec.systemPrompt(),
                "skill",StudyAgentSkills.bundled().read("course-notes"),"model",AiPromptBudget.MODEL,"embeddingModel",AliyunTextEmbeddingClient.MODEL));
            List<JSONObject> prepared=new ArrayList<>();
            for (String caseId : selectedCases) {
                int n=List.of(CASES).indexOf(caseId);
                String title="[探索] "+CASES[n];
                Long cid=j.queryForList("SELECT id FROM courses WHERE title=? AND author_id=1",Long.class,title).stream().findFirst().orElse(null);
                if (cid==null) {
                    cid=courses.create(1L,title,manifest.getString("experimentId")+"；仅在隔离数据库中").course().id();
                    var detail=courses.addSection(cid,1L,"Data Abstractions",0); long sid=detail.sections().getFirst().id();
                    for (int i=0;i<3;i++) courses.addMaterial(cid,sid,42L+i,1L,i);
                }
                for (Long media:MEDIA.get(n)) indexes.requireReady(media);
                var t=tasks.forMode(tasks.prepare(MEDIA.get(n).getFirst(),1L,null,cid,new PreparedStudyTask.Scope("MATERIALS",null,MEDIA.get(n)),GOALS[n]),"DEEP");
                if (!"BOUNDED_AGENT".equals(t.executionStrategy())) throw new AssertionError("Wrong strategy");
                var state=(JSONObject)app.getBean(StudyArtifactService.class).learningState(t);
                if (!state.getJSONArray("notes").isEmpty()) throw new IllegalStateException("Learning state contaminated");
                prepared.add(obj("caseId",CASES[n],"task",t,"initialLearningState",state));
                write(output.resolve(live?"tasks-run.json":"tasks-preflight.json"),prepared);
                if (!live) continue;
                var conversation=app.getBean(VideoConversationStore.class).create(t.anchorMediaId(),1L,cid);
                t=t.inConversation(conversation.id()); String runId=UUID.randomUUID().toString();
                var runs=app.getBean(VideoAgentRunService.class);
                runs.createQueuedRun(new VideoAgentRunMessage(runId,t.anchorMediaId(),conversation.id(),1L,cid,t.goal(),contexts.currentVersion(t.anchorMediaId()),t));
                long version=j.queryForObject("SELECT dispatch_version FROM video_agent_runs WHERE id=?",Long.class,runId);
                System.out.println("TRIAL_START "+CASES[n]+" "+runId);
                try { app.getBean(VideoAgentWorkflowService.class).process(runId,version); }
                catch (RuntimeException error) {
                    write(output.resolve(CASES[n]+"-exception.json"),obj("type",error.getClass().getName(),"message",error.getMessage()));
                    export(j,runId,output.resolve(CASES[n])); throw error;
                }
                export(j,runId,output.resolve(CASES[n]));
                var row=j.queryForMap("SELECT status,stop_code,generation_calls,retrieval_calls,unknown_calls FROM video_agent_runs WHERE id=?",runId);
                System.out.println("TRIAL_END "+CASES[n]+" "+JSON.toJSONString(row));
                if (j.queryForObject("SELECT COUNT(*) FROM ai_call_attempts WHERE run_id=? AND (usage_status<>'KNOWN' OR http_status>=400)",Integer.class,runId)>0)
                    throw new IllegalStateException("Unknown usage or provider failure: stop batch");
            }
            write(output.resolve(live?"tasks-run.json":"tasks-preflight.json"),prepared);
            System.out.println(live?"BATCH_COMPLETE":"PREFLIGHT_COMPLETE_NO_PAID_CALLS");
        }
    }
}
