package org.example.coral.controller;

import org.example.coral.analytics.TimelineService;
import org.example.coral.model.TimelineEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TimelineController {

    private final TimelineService timelineService;

    public TimelineController(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/timeline")
    public List<TimelineEvent> timeline() {
        return timelineService.buildTimeline();
    }
}
