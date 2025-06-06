package sophena.calc;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import sophena.math.energetic.Producers;
import sophena.math.energetic.SeasonalItem;
import sophena.model.HoursTrace;
import sophena.model.Producer;
import sophena.model.ProducerFunction;
import sophena.model.Project;
import sophena.model.Stats;
import sophena.model.TimeInterval;
import sophena.rcp.Workspace;

class EnergyCalculator {

	private Project project;

	private EnergyCalculator(Project project) {
		this.project = project;
	}

	public static EnergyResult calculate(Project project, CalcLog log) {
		return new EnergyCalculator(project).doIt(log);
	}

	private EnergyResult doIt(CalcLog log) {
		SolarCalcLog solarCalcLog = new SolarCalcLog();
		var bufferCalcState = new BufferCalcState(project, solarCalcLog);

		EnergyResult r = new EnergyResult(project);
		boolean[][] interruptions = interruptions(r);

		Map<Producer, SolarCalcState> solarCalcStates = new HashMap<Producer, SolarCalcState>();
		Map<Producer, HeatPumpCalcState> heatPumpCalcStates = new HashMap<Producer, HeatPumpCalcState>();

		for(Producer producer: r.producers)
		{
			if(producer.solarCollector != null & producer.solarCollectorSpec != null)
				solarCalcStates.put(producer, new SolarCalcState(solarCalcLog, project, producer));
			
			if(producer.heatPump != null)
				heatPumpCalcStates.put(producer, new HeatPumpCalcState(solarCalcLog, project, producer));
		}

		for (int hour = 0; hour < Stats.HOURS; hour++) {
			bufferCalcState.preStep(hour);
			
			if(hour == 0)
				r.bufferCapacity[hour] = bufferCalcState.CalcHTCapacity(false);

			double requiredLoad = r.loadCurve[hour];			
			double totalSuppliedPower = 0;
			double heatNetSuppliedPower = 0;
			
			for (int k = 0; k < r.producers.length; k++) {
				Producer producer = r.producers[k];

				SolarCalcState solarCalcState = solarCalcStates.get(producer);
				if(solarCalcState != null)
					solarCalcState.calcPre(hour, bufferCalcState.getTE(), bufferCalcState.getTV());
				
				HeatPumpCalcState heatPumpCalcState = heatPumpCalcStates.get(producer);
				if(heatPumpCalcState != null)
					heatPumpCalcState.calcPre(hour, bufferCalcState.getTR(), bufferCalcState.getTV());
			}
			
			double TL_i = project.weatherStation.data != null && hour < project.weatherStation.data.length
					? project.weatherStation.data[hour]
					: 0;

			// Determine if there is at least one HT producer
			boolean haveAtLeastOneHTProducer = false;
			for (int k = 0; k < r.producers.length; k++)
			{
				Producer producer = r.producers[k];
			
				SolarCalcState solarCalcState = solarCalcStates.get(producer);
				HeatPumpCalcState heatPumpCalcState = heatPumpCalcStates.get(producer);
				boolean isSolarProducer = solarCalcState != null;
				BufferCalcLoadType bufferLoadType = getProducerBufferLoadType(producer, bufferCalcState, solarCalcState, heatPumpCalcState, hour);
				
				if(bufferLoadType == BufferCalcLoadType.None)
					continue;

				// Check whether the collector is working for the current hour
				if(isSolarProducer && solarCalcState.getPhase() != SolarCalcPhase.Betrieb)
					continue;
			
				// Check whether the producer can be taken
				if (isInterrupted(k, hour, interruptions))
					continue;

				if(DoContinueOnOutdoorTemperature(producer, TL_i))
					continue;

				if(bufferLoadType == BufferCalcLoadType.HT)
					haveAtLeastOneHTProducer = true;
			}

			// Main producer loop
			for (int k = 0; k < r.producers.length; k++) {
				requiredLoad = (r.loadCurve[hour] - heatNetSuppliedPower);
				if (requiredLoad <= 0)
					break;

				Producer producer = r.producers[k];
			
				SolarCalcState solarCalcState = solarCalcStates.get(producer);
				HeatPumpCalcState heatPumpCalcState = heatPumpCalcStates.get(producer);
				boolean isSolarProducer = solarCalcState != null;
				BufferCalcLoadType bufferLoadType = getProducerBufferLoadType(producer, bufferCalcState, solarCalcState, heatPumpCalcState, hour);
				
				if(bufferLoadType == BufferCalcLoadType.None)
					continue;

				// Check whether the collector is working for the current hour
				if(isSolarProducer && solarCalcState.getPhase() != SolarCalcPhase.Betrieb)
					continue;
			
				// Check whether the producer can be taken
				if (isInterrupted(k, hour, interruptions))
					continue;
				
				if(DoContinueOnOutdoorTemperature(producer, TL_i))
					continue;
				
				double TR = bufferCalcState.getTR();
				double TV = bufferCalcState.getTV();
				
				double TK_i = TV;
				if(isSolarProducer)
					TK_i = solarCalcState.getTK_i();							
				if(heatPumpCalcState != null)
					TK_i = heatPumpCalcState.getTK_i();
				if(producer.hasProfile())
					TK_i = producer.profile.temperaturLevel[hour];

				// For NT producer calculate the power factor based on their temperature level
				double loadFactorTK_i = (bufferLoadType != BufferCalcLoadType.NT)? 1 : (TK_i - TR) / (TV - TR);
				double reducedLoad = Math.max(0, r.loadCurve[hour] * loadFactorTK_i - heatNetSuppliedPower); 
				double bufferNTUnloadLimit = Math.max(0, r.loadCurve[hour] * bufferCalcState.getNTLoadFactor(false) - heatNetSuppliedPower);
				
				// Amount of power currently needed for heatnet and buffer based on producer buffer load type
				double maxLoadRel = reducedLoad + (bufferLoadType == BufferCalcLoadType.HT ?
					bufferCalcState.CalcHTCapacity(producer.function != ProducerFunction.MAX_LOAD) :
					bufferCalcState.CalcNTCapacity(producer.function != ProducerFunction.MAX_LOAD, loadFactorTK_i));
					
				// Maximum amount of power currently needed for heatnet and buffer				
				double maxLoadAbs = reducedLoad + (bufferLoadType == BufferCalcLoadType.HT ?
					bufferCalcState.CalcHTCapacity(false) :
					bufferCalcState.CalcNTCapacity(false, loadFactorTK_i));

				// Power which can be provided by the producer
				double power = getSuppliedPower(producer, hour, solarCalcState, heatPumpCalcState, reducedLoad, maxLoadRel);
				double unloadableNTPower = Math.min(bufferNTUnloadLimit, bufferCalcState.totalUnloadableNTPower());
				
				if(!isSolarProducer)
				{
					double unloadablePower = bufferCalcState.totalUnloadableHTPower() + bufferCalcState.totalUnloadableVTPower() + (haveAtLeastOneHTProducer ? unloadableNTPower : 0);
					// Don't use expensive peek load producer if the buffer has enough HT, VT and NT power left to satisfy the heatnet 
					if((unloadablePower > requiredLoad) && producer.function == ProducerFunction.PEAK_LOAD)
						continue;
					
					// Don't use base load producer if buffer is still above base load limit after required unload to satisfy the heatnet
					if(bufferCalcState.getMaxTargetLoadStillReachedAfterPartialUnload(requiredLoad) && (unloadablePower > requiredLoad) && producer.function == ProducerFunction.BASE_LOAD)
						continue;
				}
				
				// Don't limit producer unless they exceed the maximum power currently needed, allways use solar producer 
				if(power <= maxLoadAbs)
				{
					if(bufferLoadType ==  BufferCalcLoadType.HT && producer.function == ProducerFunction.PEAK_LOAD && bufferCalcState.totalUnloadableNTPower() > 0)
					{
						double p = Math.min(unloadableNTPower, power - Producers.minPower(producer, solarCalcState, heatPumpCalcState, hour));
						bufferCalcState.unload(hour, p, BufferCalcLoadType.NT);
						totalSuppliedPower += p; 
						heatNetSuppliedPower += p;
						power -= p;
						r.suppliedBufferHeat[hour] += p;
						reducedLoad -= p;
					}	

					if(haveAtLeastOneHTProducer || bufferLoadType != BufferCalcLoadType.NT)
					{
						// Mainly use producer power for the heatnet and leftover to charge the buffer 
						double surplus = power - reducedLoad;	
						heatNetSuppliedPower += surplus > 0 ? power - surplus : power;
						if(surplus > 0)	
							power -= bufferCalcState.load(hour, surplus, bufferLoadType, false, loadFactorTK_i);		
					}
					else
						power = 0;
					
					totalSuppliedPower += power;					
					r.producerResults[k][hour] = power;
				}
				
				// Write back used power in order to heat up the collector with the not used part
				if(isSolarProducer)
					solarCalcState.setConsumedPower(power * 1000);
				
				if(heatPumpCalcState != null)
					heatPumpCalcState.setConsumedPower(power * 1000);
			} 
			// end producer loop
			
			for (int k = 0; k < r.producers.length; k++) {
				Producer producer = r.producers[k];

				SolarCalcState solarCalcState = solarCalcStates.get(producer);
				if(solarCalcState != null)
					solarCalcState.calcPost(hour);
				
				HeatPumpCalcState heatPumpCalcState = heatPumpCalcStates.get(producer);
				if(heatPumpCalcState != null)
					heatPumpCalcState.calcPost(hour);
			}
			
			requiredLoad = (r.loadCurve[hour] - heatNetSuppliedPower);
			if (requiredLoad >= 0) {
				
				double bufferNTUnloadLimit = Math.max(0, r.loadCurve[hour] * bufferCalcState.getNTLoadFactor(false) - heatNetSuppliedPower);
				
				double remainingRequiredLoad = bufferCalcState.unload(hour, requiredLoad, BufferCalcLoadType.HT);
				
				if(remainingRequiredLoad > 0)
					remainingRequiredLoad = bufferCalcState.unload(hour, remainingRequiredLoad, BufferCalcLoadType.VT);
				
				if(remainingRequiredLoad > 0 && haveAtLeastOneHTProducer)
				{
					double p = remainingRequiredLoad - Math.min(bufferNTUnloadLimit, remainingRequiredLoad);
					remainingRequiredLoad = bufferCalcState.unload(hour, Math.min(bufferNTUnloadLimit, remainingRequiredLoad), BufferCalcLoadType.NT) + p;					
				}
				
				double bufferPower = requiredLoad - remainingRequiredLoad;
				totalSuppliedPower += bufferPower;
				r.suppliedBufferHeat[hour] += bufferPower; 
			}

			// buffer capacity with buffer loss
			r.bufferLoss[hour] = bufferCalcState.applyLoss(hour);

			r.suppliedPower[hour] = totalSuppliedPower;

			if ((hour + 1) < Stats.HOURS) {
				r.bufferCapacity[hour + 1] = bufferCalcState.CalcHTCapacity(false);
			}
			
			bufferCalcState.postStep(hour);

		} // end hour loop

		for (int k = 0; k < r.producers.length; k++) {
			Producer producer = r.producers[k];

			SolarCalcState solarCalcState = solarCalcStates.get(producer);
			if(solarCalcState != null)
				r.producerStagnationDays[k] = solarCalcState.getNumStagnationDays();
			
			HeatPumpCalcState heatPumpCalcState = heatPumpCalcStates.get(producer);
			if(heatPumpCalcState != null)
				r.producerJaz[k] = heatPumpCalcState.getJAZ();
		}

		
		double[] targetChargeLevels = new double[Stats.HOURS];
		double[] flowTemperatures = new double[Stats.HOURS];
		double[] returnTemperatures = new double[Stats.HOURS];
		double minWeatherStationTemperature = project.weatherStation.minTemperature(); 
		double maxConsumerHeatingLimit = project.maxConsumerHeatTemperature();
		for (int hour = 0; hour < Stats.HOURS; hour++) {
			double temperature = project.weatherStation.data != null && hour < project.weatherStation.data.length
					? project.weatherStation.data[hour]
							: 0;
			var item = SeasonalItem.calc(project.heatNet, hour, minWeatherStationTemperature, maxConsumerHeatingLimit, temperature);
			
			targetChargeLevels[hour] = item.targetChargeLevel;
			flowTemperatures[hour] = item.flowTemperature;
			returnTemperatures[hour] = item.returnTemperature;
		}
		
		try {
			var logDir = new File(Workspace.dir(), "log");
			var filename = logDir.getAbsolutePath() + "SolarCalcLog.log";
			try(java.io.PrintWriter pw = new java.io.PrintWriter(filename))
			{
				pw.println(solarCalcLog.toString());
			}

			SolarCalcLog.writeCsv(logDir.getAbsolutePath() + "seasonal_targetchargelevels.csv", targetChargeLevels);
			SolarCalcLog.writeCsv(logDir.getAbsolutePath() + "seasonal_TV.csv", flowTemperatures);
			SolarCalcLog.writeCsv(logDir.getAbsolutePath() + "seasonal_TR.csv", returnTemperatures);
		}
		catch(java.io.FileNotFoundException err)
		{
		}

		calcTotals(r);

		return r;
	}
	
	private boolean DoContinueOnOutdoorTemperature(Producer producer, double TL_i)
	{
		boolean result = false;
		if(producer.isOutdoorTemperatureControl)
		{
			switch (producer.outdoorTemperatureControlKind){
			case From:
				if(producer.outdoorTemperature > TL_i)
					result = true;
				break;
			case Until:
				if(producer.outdoorTemperature < TL_i)
					result = true;
				break;
			default:
				throw new IllegalArgumentException("Unexpected value: " + producer.outdoorTemperatureControlKind);
			}
		}
		return result;
	}
	
	private BufferCalcLoadType getProducerBufferLoadType(Producer producer, BufferCalcState bufferCalcState, SolarCalcState solarCalcState, HeatPumpCalcState heatPumpCalcState, int hour)
	{
		if(producer.hasProfile())
		{
			double temperature = producer.profile.temperaturLevel[hour];
			
			if(temperature >= bufferCalcState.getTMAX())
				return BufferCalcLoadType.HT;
			
			if(temperature >= bufferCalcState.getTV())
				return BufferCalcLoadType.VT;
			
			if(temperature >= bufferCalcState.getTR())
				return BufferCalcLoadType.NT;
			
			return BufferCalcLoadType.None;
		}

		switch(producer.productGroup.type)
		{
		case HEAT_PUMP:
			return heatPumpCalcState.getBufferLoadType();
		case SOLAR_THERMAL_PLANT:
			 return solarCalcState.getBufferLoadType();
		default:
			return BufferCalcLoadType.HT;
		}
	}

	private double getSuppliedPower(Producer producer, int hour, SolarCalcState solarCalcState,
		HeatPumpCalcState heatPumpCalcState,
		double requiredLoad, double maxLoad) {
		double bMin = Producers.minPower(producer, solarCalcState, heatPumpCalcState, hour);
		double bMax = Producers.maxPower(producer, solarCalcState, heatPumpCalcState, hour);
		double load = producer.function == ProducerFunction.PEAK_LOAD 
				? requiredLoad
				: maxLoad;
		return Math.min(Math.max(load, bMin), bMax);
	}

	private void calcTotals(EnergyResult r) {
		r.totalLoad = Stats.sum(r.loadCurve);
		for (int i = 0; i < r.producers.length; i++) {
			Producer p = r.producers[i];
			double total = Stats.sum(r.producerResults[i]);
			r.totalHeats.put(p.id, total);
			r.totalProducedHeat += total;
		}
		r.totalBufferedHeat = Stats.sum(r.suppliedBufferHeat);
		r.totalBufferLoss = Stats.sum(r.bufferLoss);
	}

	private boolean[][] interruptions(EnergyResult r) {
		boolean[][] interruptions = new boolean[r.producers.length][];
		for (int i = 0; i < r.producers.length; i++) {
			Producer p = r.producers[i];
			if (p == null || p.interruptions.isEmpty())
				continue;
			boolean[] interruption = new boolean[Stats.HOURS];
			for (TimeInterval time : p.interruptions) {
				int[] interval = HoursTrace.getHourInterval(time);
				HoursTrace.applyInterval(interruption, interval, (old, idx) -> {
					return true;
				});
			}
			interruptions[i] = interruption;
		}
		return interruptions;
	}

	private boolean isInterrupted(int producer, int hour,
			boolean[][] interruptions) {
		boolean[] interruption = interruptions[producer];
		if (interruption == null)
			return false;
		return interruption[hour];
	}
}
