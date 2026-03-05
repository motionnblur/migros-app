package com.example.MigrosBackend.controller.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.profile.UserProfileService;
import com.stripe.model.Token;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserProfileControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private TokenService tokenService;

    private UserProfileTableDto userProfileTableDto;

    @BeforeEach
    void setup() {
        userProfileTableDto = new UserProfileTableDto();
        userProfileTableDto.setUserFirstName("John");
        userProfileTableDto.setUserLastName("Doe");
        userProfileTableDto.setUserAddress("123 Main St");
        userProfileTableDto.setUserAddress2("Apt 4B");
        userProfileTableDto.setUserTown("Townsville");
        userProfileTableDto.setUserCountry("Countryland");
        userProfileTableDto.setUserPostalCode("12345");
    }

    @Test
    void uploadUserProfileTable_shouldReturnOk() throws Exception {
        doNothing().when(userProfileService).uploadUserProfileTable(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()
        );

        mockMvc.perform(post("/user/profile/uploadUserProfileTable")
                        .param("userFirstName", "John")
                        .param("userLastName", "Doe")
                        .param("userAddress", "123 Main St")
                        .param("userAddress2", "Apt 4B")
                        .param("userTown", "Townsville")
                        .param("userCountry", "Countryland")
                        .param("userPostalCode", "12345")
                        .param("token", "sample-token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
    }

    @Test
    void getUserProfileTable_shouldReturnProfileDto() throws Exception {
        when(userProfileService.getUserProfileTable(anyString())).thenReturn(userProfileTableDto);

        mockMvc.perform(get("/user/profile/getUserProfileTable")
                        .param("token", "sample-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                            "userFirstName":"John",
                            "userLastName":"Doe",
                            "userAddress":"123 Main St",
                            "userAddress2":"Apt 4B",
                            "userTown":"Townsville",
                            "userCountry":"Countryland",
                            "userPostalCode":"12345"
                        }
                        """));
    }
}