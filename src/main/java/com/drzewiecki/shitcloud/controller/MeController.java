package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.model.Me;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import com.drzewiecki.shitcloud.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@PreAuthorize("isAuthenticated()")
public class MeController {

    @GetMapping
    public Me me() {
        CurrentUser user = AuthenticationUtils.currentUser();
        return new Me(user.id(), user.displayName(), user.groups(), user.builder(), user.admin(), user.guest());
    }
}
