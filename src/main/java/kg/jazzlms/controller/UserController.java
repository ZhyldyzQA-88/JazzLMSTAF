package kg.jazzlms.controller;

import io.restassured.response.Response;
import kg.jazzlms.http.ApiRequest;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.UserResponse;
import kg.jazzlms.utils.JsonUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static kg.jazzlms.constants.Endpoints.USERS;
import static kg.jazzlms.constants.Endpoints.USERS_ID;

public class UserController extends ApiRequest {

    public UserController(String url) {
        super(url);
    }

    public UserController(String url, String token) {
        super(url, token);
    }

    public UserResponse createUser(UserRequest request) {
        return post(USERS, JsonUtils.toJson(request)).as(UserResponse.class);
    }

    public List<UserResponse> getAllUsers() {
        return Arrays.asList(super.get(USERS).as(UserResponse[].class));
    }

    public UserResponse getUserById(String id) {
        return get(USERS + "/" + id).as(UserResponse.class);
    }

    public Response createUserResponse(UserRequest request) {
        return post(USERS, JsonUtils.toJson(request));
    }

    // для битого JSON, неверных типов и пустого тела
    public Response createUserRaw(String body) {
        return post(USERS, body);
    }

    /** PUT /api/users/{id}: все поля необязательные, отправляем только нужные */
    public Response updateUserResponse(UUID id, Map<String, Object> fields) {
        return put(String.format(USERS_ID, id), JsonUtils.toJson(fields));
    }

    public Response deleteUserResponse(UUID id) {
        return delete(String.format(USERS_ID, id));
    }
}
