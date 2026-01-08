package com.collabwhiteboard.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class ClientApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        primaryStage.setTitle("Collaborative Whiteboard (TCP)");
        Scene scene = new Scene(root, 1100, 700);
        // Attach global stylesheet for a more polished look
        scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
        // Optional window icon if provided
        try {
            Image icon = new Image(getClass().getResourceAsStream("/icons/whiteboard.png"));
            primaryStage.getIcons().add(icon);
        } catch (Exception ignored) {
        }
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(600);
        primaryStage.centerOnScreen();
        primaryStage.show();

        primaryStage.setOnCloseRequest(e -> {
            MainController controller = loader.getController();
            controller.shutdown();
            Platform.exit();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}


