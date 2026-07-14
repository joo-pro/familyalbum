package com.joopapa.familyalbum;

import com.joopapa.familyalbum.auth.AuthProperties;
import com.joopapa.familyalbum.config.AppCorsProperties;
import com.joopapa.familyalbum.push.PushProperties;
import com.joopapa.familyalbum.storage.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AppCorsProperties.class, StorageProperties.class, PushProperties.class, AuthProperties.class})
public class FamilyAlbumApplication {

	public static void main(String[] args) {
		SpringApplication.run(FamilyAlbumApplication.class, args);
	}

}