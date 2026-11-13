package com.goeFast.GeoFastPe.dto.response;

import com.goeFast.GeoFastPe.entity.User;

public record UserChatResponse(Long id, String username, String name, String city, String avatar) {
    public static UserChatResponse from(User user) {
        return new UserChatResponse(user.getId(), user.getUsername(), user.getName(), user.getCity(), user.getAvatar());
    }
}
