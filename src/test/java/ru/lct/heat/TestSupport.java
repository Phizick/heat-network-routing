package ru.lct.heat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.geo.Projection;
import ru.lct.heat.ingest.IngestProcessor;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.model.ScenarioAssembler;
import ru.lct.heat.route.Context;

/** Конфиг из application.yml и конкурсный датасет без поднятия Spring и БД. */
public final class TestSupport {

    private TestSupport() {
    }

    public static HeatProperties props() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env)).bind("heat", HeatProperties.class).get();
    }

    public static Scenario scenario(String resource, HeatProperties props) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ScenarioAssembler assembler = new ScenarioAssembler(mapper);
        try (InputStream in = TestSupport.class.getResourceAsStream(resource)) {
            new IngestProcessor(mapper, new Projection(props.getCrs().getCalc()),
                    props.getIngest().getKnownRestrictionTypes(), 100).process(in, assembler);
        }
        return assembler.scenario();
    }

    public static Context context(String resource) throws IOException {
        HeatProperties props = props();
        return new Context(scenario(resource, props), new Rules(props), 32637);
    }
}
