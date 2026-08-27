package pl.chrisitstyle.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "user-service")
public interface UserFeignClient {

    @GetMapping("/users/{id}")
    UserResponse getUser(
            @PathVariable("id") Long userId
    );

    @GetMapping("/users/by-keycloak-subject")
    UserResponse getUserByKeycloakSubject(
            @RequestParam("subject") String keycloakSubject
    );
}