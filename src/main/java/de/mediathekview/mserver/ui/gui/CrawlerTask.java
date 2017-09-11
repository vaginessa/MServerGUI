package de.mediathekview.mserver.ui.gui;

import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.AtomicDouble;

import de.mediathekview.mlib.daten.Sender;
import de.mediathekview.mlib.progress.Progress;
import de.mediathekview.mserver.crawler.CrawlerManager;
import de.mediathekview.mserver.progress.listeners.SenderProgressListener;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.PieChart.Data;
import javafx.scene.chart.XYChart.Series;

public class CrawlerTask extends Task<Void>
{
    private final ObservableList<Sender> senderToCrawl;
    private final AtomicDouble progressSum;
    private final ObservableList<Data> pieChartData;
    private final PieChart.Data dataError;
    private final PieChart.Data datafinished;
    private final PieChart.Data dataWorking;

    private final ConcurrentHashMap<Sender, AtomicLong> senderMaxCounts;
    private final ConcurrentHashMap<Sender, AtomicLong> senderActualCounts;
    private final ConcurrentHashMap<Sender, AtomicLong> senderErrorCounts;
    private final ObservableList<Series<String, Number>> processChartData;
    private final ConcurrentHashMap<Series<String, Number>, BarChart.Data<String, Number>> senderSeriesData;
    private final ConcurrentHashMap<Sender, Series<String, Number>> senderSerieses;
    private final ConcurrentHashMap<Sender, ConcurrentHashMap<String, AtomicLong>> senderThreadData;

    public CrawlerTask(final ResourceBundle aResourceBundle, final ObservableList<Data> aCrawlerStatisticData,
            final ObservableList<Series<String, Number>> aProcessChartData, final ObservableList<Sender> aSender)
    {
        senderToCrawl = aSender;

        pieChartData = aCrawlerStatisticData;
        dataError = new PieChart.Data(aResourceBundle.getString("chart.error"), 0);
        datafinished = new PieChart.Data(aResourceBundle.getString("chart.finished"), 0);
        dataWorking = new PieChart.Data(aResourceBundle.getString("chart.working"), 0);
        pieChartData.add(dataError);
        pieChartData.add(datafinished);
        pieChartData.add(dataWorking);
        pieChartData.forEach(
                data -> data.nameProperty().bind(Bindings.concat(data.getName(), " ", data.pieValueProperty())));

        senderThreadData = new ConcurrentHashMap<>();
        senderSerieses = new ConcurrentHashMap<>();
        processChartData = aProcessChartData;
        for (final Sender sender : aSender)
        {
            final Series<String, Number> senderSeries = new Series<>();
            senderSeries.setName(sender.getName());
            senderSerieses.put(sender, senderSeries);
            processChartData.add(senderSeries);
        }
        senderSeriesData = new ConcurrentHashMap<>();

        progressSum = new AtomicDouble(0);
        senderMaxCounts = new ConcurrentHashMap<>();
        senderActualCounts = new ConcurrentHashMap<>();
        senderErrorCounts = new ConcurrentHashMap<>();
        senderToCrawl.forEach(s -> {
            senderMaxCounts.put(s, new AtomicLong(0));
            senderActualCounts.put(s, new AtomicLong(0));
            senderErrorCounts.put(s, new AtomicLong(0));
        });
    }

    @Override
    protected Void call()
    {
        final LoadListener listener = new LoadListener();

        final CrawlerManager crawlerManager = CrawlerManager.getInstance();
        crawlerManager.addSenderProgressListener(listener);
        crawlerManager.startCrawlerForSender(senderToCrawl.toArray(new Sender[senderToCrawl.size()]));

        crawlerManager.removeSenderProgressListener(listener);
        return null;
    }

    class LoadListener implements SenderProgressListener
    {
        private final ConcurrentLinkedQueue<SenderProgressWraper> progressQue;

        public LoadListener()
        {
            progressQue = new ConcurrentLinkedQueue<>();
        }

        @Override
        public void updateProgess(final Sender aSender, final Progress aProgress)
        {
            progressQue.offer(new SenderProgressWraper(aProgress, aSender));
            addNewThreadData(aSender, aProgress);
            Platform.runLater(() -> updateForProgressFromQue());
        }

        private void addNewThreadData(final Sender aSender, final Progress aProgress)
        {
            if (aProgress.getActualCount() > 0 || aProgress.getErrorCount() > 0)
            {
                final String threadName = Thread.currentThread().getName();
                final ConcurrentHashMap<String, AtomicLong> senderData;
                if (senderThreadData.containsKey(aSender))
                {
                    senderData = senderThreadData.get(aSender);
                }
                else
                {
                    senderData = new ConcurrentHashMap<>();
                    senderThreadData.put(aSender, senderData);
                }
                AtomicLong threadCount;
                if (senderData.containsKey(threadName))
                {
                    threadCount = senderData.get(threadName);
                }
                else
                {
                    threadCount = new AtomicLong(0);
                }

                threadCount.getAndIncrement();
                senderData.put(threadName, threadCount);
            }
        }

        private void updateForProgressFromQue()
        {
            final SenderProgressWraper progressWrapper = progressQue.poll();
            updateSenderStatistic(progressWrapper.getSender(), progressWrapper.getProgress());
            updateStatisticData();
            progressSum.getAndSet(progressWrapper.getProgress().calcProgressInPercent() / senderToCrawl.size());
            updateProgress(progressSum.get(), 100);

            updateThreadChart();
        }

        private void updateThreadChart()
        {
            for (final Entry<Sender, ConcurrentHashMap<String, AtomicLong>> senderDataEntry : senderThreadData
                    .entrySet())
            {
                final Series<String, Number> senderSeries = senderSerieses.get(senderDataEntry.getKey());
                for (final Entry<String, AtomicLong> threadDataEntry : senderDataEntry.getValue().entrySet())
                {

                    if (senderSeriesData.contains(senderSeries))
                    {
                        senderSeriesData.get(senderSeries).setYValue(threadDataEntry.getValue().get());
                    }
                    else
                    {
                        final BarChart.Data<String, Number> newSenderSeriesData =
                                new BarChart.Data<>(threadDataEntry.getKey(), threadDataEntry.getValue().get());

                        senderSeriesData.put(senderSeries, newSenderSeriesData);
                        senderSeries.getData().add(newSenderSeriesData);
                    }
                }
            }
        }

        private void updateStatisticData()
        {
            long maxCount = 0;
            long errorCount = 0;
            long actualCount = 0;
            for (final Sender sender : senderToCrawl)
            {
                maxCount += senderMaxCounts.get(sender).get();
                errorCount += senderErrorCounts.get(sender).get();
                actualCount += senderActualCounts.get(sender).get();
            }
            dataError.setPieValue(errorCount);
            datafinished.setPieValue(actualCount);
            dataWorking.setPieValue((double) maxCount - actualCount);

        }

        private void updateSenderStatistic(final Sender aSender, final Progress aProgress)
        {
            senderMaxCounts.get(aSender).getAndSet(aProgress.getMaxCount());
            senderErrorCounts.get(aSender).getAndSet(aProgress.getErrorCount());
            senderActualCounts.get(aSender).getAndSet(aProgress.getActualCount());
        }

    }

}
