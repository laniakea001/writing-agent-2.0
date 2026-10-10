package com.writingagent.v2.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** AgentScope 2.0.1/business DTOs use Jackson 2 JsonNode. Boot 4's Jackson 3
 * default cannot deserialize it. Keep this explicit compatibility seam until
 * the SDK/DTO migration is reviewed; never enable polymorphic default typing.
 */
@Configuration
@SuppressWarnings("removal")
public class DocumentHttpJsonConfiguration implements WebMvcConfigurer {
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        var json=new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        converters.add(0,new MappingJackson2HttpMessageConverter(json));
    }
}
