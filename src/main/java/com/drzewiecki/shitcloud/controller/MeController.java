package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.model.Me;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class MeController {

    /** Until Auth0 is wired in, everyone is this builder. */
    static final Me MOCK_USER = new Me("filip", "Filip", List.of("shitcloud-builders"), true, true);

    @GetMapping
    public Me me() {
        return MOCK_USER;
    }
}
