import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.response.UserResponse;
import kg.jazzlms.utils.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

@Tag("regression")
@Epic("User service")
@Feature("Пользователи: базовые сценарии")
public class UserTest {

    private final UserController userController = new UserController(Endpoints.BASE_URL);

    @Tag("smoke")
    @Test
    public void testGetUsers() {
        List<UserResponse> users = userController.getAllUsers();
        System.out.println(users);
    }

    @Test
    public void getUserByID() {
        System.out.println(userController.getUserById("5f6602f3-42bf-4942-86f2-1c3164cab0f3"));
    }

    @Test
    public void createUserTest() {
        UserResponse userResponse = userController.createUser(EntityManager.generateUser());

    }



}