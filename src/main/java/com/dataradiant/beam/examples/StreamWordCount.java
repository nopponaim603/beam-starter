/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dataradiant.beam.examples;

import org.apache.beam.runners.flink.FlinkPipelineOptions;
import org.apache.beam.runners.flink.FlinkRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import com.google.common.collect.ImmutableMap;
import org.apache.flink.api.common.aggregators.Aggregator;
import org.joda.time.Duration;
import java.util.Arrays;

public class StreamWordCount {

  public static class ExtractWordsFn extends DoFn<String, String> {
    private final Aggregator<Long, Long> emptyLines =
        createAggregator("emptyLines", new Sum.SumLongFn());

    @Override
    public void processElement(ProcessContext c) {
      if (c.element().trim().isEmpty()) {
        emptyLines.aggregate(1L);
        //emptyLines.addValue(1L);
      }

      // Split the line into words.
      String[] words = c.element().split("[^a-zA-Z']+");

      // Output each word encountered into the output PCollection.
      for (String word : words) {
        if (!word.isEmpty()) {
          c.output(word);
        }
      }
    }
  }

  public static class CountWords extends PTransform<PCollection<String>,
                    PCollection<KV<String, Long>>> {
    @Override
    public PCollection<KV<String, Long>> apply(PCollection<String> lines) {

      // Convert lines of text into individual words.
      PCollection<String> words = lines.apply(
          ParDo.of(new ExtractWordsFn()));

      // Count the number of times each word occurs.
      PCollection<KV<String, Long>> wordCounts =
          words.apply(Count.<String>perElement());

      return wordCounts;
    }
  }

  /** A SimpleFunction that converts a Word and Count into a printable string. */
  public static class FormatAsTextFn extends SimpleFunction<KV<String, Long>, String> {
    @Override
    public String apply(KV<String, Long> input) {
      return input.getKey() + ": " + input.getValue();
    }
  }

  // public static class ValuesFn extends SimpleFunction<KV<byte[], String>, String> {
  //   @Override
  //   public String apply(KV<byte[], String> input) {
  //     return input.getValue();
  //   }
  // }

  // public static class TrivialCountFn extends SimpleFunction<String, KV<String, Long>> {
  //   @Override
  //   public KV<String, Long> apply(String input) {
  //     return KV.of(input, 1L);
  //   }
  // }

  /**
   * Options supported by {@link WordCount}.
   * <p>
   * Inherits standard configuration options.
   */
  public interface Options extends PipelineOptions, FlinkPipelineOptions {
    @Description("Path of the file to read from")
    @Default.String("/tmp/kinglear.txt")
    String getInput();
    void setInput(String value);

    @Description("Path of the file to write to")
    @Default.String("/tmp/output.txt")
    String getOutput();
    void setOutput(String value);

    @Description("Fixed window duration, in minutes")
    @Default.Integer(1)
    Integer getWindowSize();
    void setWindowSize(Integer value);
  }

  public static void main(String[] args) throws Exception {

    Options options = PipelineOptionsFactory.fromArgs(args).withValidation()
        .as(Options.class);
    options.setRunner(FlinkRunner.class);

    Pipeline p = Pipeline.create(options);

    KafkaIO.Read<byte[], String> kafkaIOReader = KafkaIO.read()
        .withBootstrapServers("192.168.99.100:32771")
        .withTopics(Arrays.asList("beam".split(",")))
        .updateConsumerProperties(ImmutableMap.of("auto.offset.reset", (Object)"earliest"))
        .withValueCoder(StringUtf8Coder.of());

    p.apply(kafkaIOReader.withoutMetadata())
        .apply(Values.<String>create())
        .apply(Window.<String>into(
          FixedWindows.of(Duration.standardMinutes(options.getWindowSize()))))
        .apply(new CountWords())
        .apply(MapElements.via(new FormatAsTextFn()))
        .apply("WriteCounts", TextIO.Write.to(options.getOutput()));

    p.run();
  }

}