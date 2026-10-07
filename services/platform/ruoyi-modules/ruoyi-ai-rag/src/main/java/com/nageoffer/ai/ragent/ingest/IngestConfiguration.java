package com.nageoffer.ai.ragent.ingest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Parser adapter configuration belongs to the RAG module, independently of runtime. */
@Configuration
@EnableConfigurationProperties(LocalMinerUProperties.class)
public class IngestConfiguration { }
