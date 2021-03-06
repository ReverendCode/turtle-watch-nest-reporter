package com.islandturtlewatch.nest.reporter.transport;

import com.googlecode.objectify.annotation.Entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Builder;

@Entity
@Builder(fluent=false)
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReportRequest {
  @Getter @Setter
  private String reportRefEncoded;
  @Getter @Setter
  private String reportEncoded;
}
