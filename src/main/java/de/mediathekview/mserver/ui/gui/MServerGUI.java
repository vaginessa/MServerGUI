package de.mediathekview.mserver.ui.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.controlsfx.control.ListSelectionView;

import de.mediathekview.mlib.daten.Sender;
import de.mediathekview.mlib.filmlisten.FilmlistFormats;
import de.mediathekview.mserver.crawler.CrawlerManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.PieChart.Data;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ProgressBar;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;

//TODO Add information que for Messages and Progress + Statistic. Other Threads add info to que. One FX Task runs the que and updates the vui.
public class MServerGUI extends Application
{

    private static final Logger LOG = LogManager.getLogger(MServerGUI.class);
    private static final String FILE_EXTENSION_SEPERATOR = ".";

    private static final String BUNDLE_KEY_SELECTION_VIEW_TARGET = "selectionView.target";

    private static final String BUNDLE_KEY_SELECTION_VIEW_SOURCE = "selectionView.source";
    private static final String CONSOLE_PATTERN = "%s - %s";

    private final CrawlerManager crawlerManager;

    @FXML
    private MenuBar menuBar;

    @FXML
    private ListSelectionView<Sender> crawlerSelectionView;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private PieChart statisticChart;

    @FXML
    private ListView<String> messageList;

    @FXML
    private Button startButton;

    @FXML
    private Button saveButton;

    @FXML
    private Button startImport;

    @FXML
    private Button startImportUrl;

    @FXML
    private CheckBox debugCheckBox;

    private final ResourceBundle bundle;
    private ObservableList<String> messages;
    private ObservableList<Data> pieChartData;

    public MServerGUI()
    {
        crawlerManager = CrawlerManager.getInstance();
        bundle = ResourceBundle.getBundle("MServerGUI", Locale.getDefault());
    }

    @Override
    public void start(final Stage aPrimaryStage) throws Exception
    {
        final Parent root = FXMLLoader.load(getClass().getClassLoader().getResource("fxml/MServerGUI.fxml"), bundle);

        final Scene scene = new Scene(root);
        aPrimaryStage.setScene(scene);

        final MessageTask messageTask = new MessageTask();
        messageTask.valueProperty().addListener(new ChangeListener<MessageWrapper>()
        {

            @Override
            public void changed(final ObservableValue<? extends MessageWrapper> aObservable,
                    final MessageWrapper aOldValue, final MessageWrapper aNewValue)
            {
                Platform.runLater(() -> {
                    printMessage(aNewValue);
                });
            }
        });

        crawlerManager.addMessageListener(messageTask);

        aPrimaryStage.setOnCloseRequest((event) -> messageTask.setShouldRun(false));

        aPrimaryStage.show();
    }

    @FXML
    public void initialize()
    {
        crawlerSelectionView
                .setSourceItems(FXCollections.observableArrayList(crawlerManager.getAviableSenderToCrawl()));
        crawlerSelectionView.setSourceHeader(new Label(bundle.getString(BUNDLE_KEY_SELECTION_VIEW_SOURCE)));
        crawlerSelectionView.setTargetHeader(new Label(bundle.getString(BUNDLE_KEY_SELECTION_VIEW_TARGET)));

        crawlerSelectionView.getTargetItems().addListener((ListChangeListener<Sender>) event -> checkStartButton());

        pieChartData = FXCollections.observableArrayList();
        statisticChart.setData(pieChartData);

        messages = FXCollections.observableArrayList();
        messageList.setItems(messages);
    }

    @FXML
    protected void quit()
    {
        Platform.exit();
    }

    @FXML
    public void startCrawler()
    {
        final CrawlerTask crawlerTask = new CrawlerTask(bundle, pieChartData, crawlerSelectionView.getTargetItems());
        progressBar.progressProperty().bind(crawlerTask.progressProperty());
        new Thread(crawlerTask).start();
    }

    @FXML
    public void openSaveDialog()
    {
        // TODO
    }

    @FXML
    public void openFileImportDialog(final Event aEvent)
    {
        try
        {
            boolean hasError = false;
            do
            {
                final FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Open Resource File");

                final List<ExtensionFilter> extensionFilters = Arrays.stream(FilmlistFormats.values())
                        .map(this::toExtensionFilter).collect(Collectors.toList());
                fileChooser.getExtensionFilters().addAll(extensionFilters);

                final File selectedFile = fileChooser.showOpenDialog(eventToStage(aEvent));
                if (selectedFile != null)
                {
                    final Path selectedPath = selectedFile.toPath();
                    if (Files.exists(selectedPath) && Files.isReadable(selectedPath))
                    {
                        hasError = false;
                        final FilmlistImportTask importTask = new FilmlistImportTask(eventToStage(aEvent), bundle,
                                FilmlistFormats.valueOf(fileChooser.getSelectedExtensionFilter().getDescription()),
                                selectedPath.toAbsolutePath().toString());
                        importTask.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED,
                                (final WorkerStateEvent t) -> enableControls());
                        disableControls();
                        new Thread(importTask).start();
                    }
                    else
                    {
                        hasError = true;
                    }
                }
            }
            while (hasError);
        }
        catch (final IOException ioException)
        {
            LOG.fatal("Unexpected error while importing the film list.", ioException);
            throw new IllegalStateException(ioException);
        }
    }

    private void disableControls()
    {
        startButton.setDisable(true);
        saveButton.setDisable(true);
        startImport.setDisable(true);
        startImportUrl.setDisable(true);
        menuBar.setDisable(true);
    }

    private void enableControls()
    {
        checkStartButton();
        saveButton.setDisable(false);
        startImport.setDisable(false);
        startImportUrl.setDisable(false);
        menuBar.setDisable(false);
    }

    private void checkStartButton()
    {
        startButton.setDisable(crawlerSelectionView.getTargetItems().isEmpty());
    }

    @FXML
    public void openUrlImportDialog(final Event aEvent) throws IOException
    {
        final ImportUrlDialog importUrlDialog = new ImportUrlDialog(bundle);
        final Optional<ImportUrlResult> result = importUrlDialog.showAndWait();

        if (result.isPresent())
        {
            try
            {
                final FilmlistImportTask importTask = new FilmlistImportTask(eventToStage(aEvent), bundle,
                        result.get().getFilmlistFormats(), result.get().getUrl());
                importTask.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED,
                        (final WorkerStateEvent t) -> enableControls());
                new Thread(importTask).start();
            }
            catch (final IOException ioException)
            {
                LOG.fatal("Unexpected error while importing the film list.", ioException);
                throw new IllegalStateException(ioException);
            }
            disableControls();
        }

    }

    public static final Stage eventToStage(final Event aEvent)
    {
        return Stage.class.cast(Control.class.cast(aEvent.getSource()).getScene().getWindow());
    }

    public ExtensionFilter toExtensionFilter(final FilmlistFormats aFilmlistFormats)
    {
        return new ExtensionFilter(aFilmlistFormats.name(),
                FILE_EXTENSION_SEPERATOR + aFilmlistFormats.getFileExtension());
    }

    private void printMessage(final MessageWrapper aMessageWrapper)
    {
        switch (aMessageWrapper.getType())
        {
        case DEBUG:
            if (debugCheckBox.isSelected())
            {
                messageToConsole(aMessageWrapper.getMessage());
            }
            break;
        case INFO:
        case WARNING:
            messageToConsole(aMessageWrapper.getMessage());
            break;
        default:
            messageToDialog(aMessageWrapper.getMessage());
        }
    }

    private void messageToDialog(final String aMessage)
    {
        final Alert alert = new Alert(AlertType.ERROR);
        final Text text = new Text(aMessage);
        text.setWrappingWidth(alert.getWidth());
        alert.getDialogPane().setContent(text);
        alert.show();
    }

    private void messageToConsole(final String aMessageText)
    {
        messages.add(String.format(CONSOLE_PATTERN,
                LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)), aMessageText));

    }

}
