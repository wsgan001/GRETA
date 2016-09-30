package transaction;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import event.*;
import query.Query;

public class Sase extends Transaction {
	
	Query query;
	HashMap<Integer,Integer> number_of_events_at_second;
	
	public Sase (Stream str, Query q, CountDownLatch d, AtomicLong time, AtomicInteger mem) {		
		super(str,d,time,mem);
		query = q;
		number_of_events_at_second = new HashMap<Integer,Integer>();
	}
	
	public void run () {
		
		long start =  System.currentTimeMillis();
		computeResults();
		long end =  System.currentTimeMillis();
		long duration = end - start;
		total_cpu.set(total_cpu.get() + duration);			
		done.countDown();
	}
	
	public void computeResults () {
		
		Set<String> substream_ids = stream.substreams.keySet();
		
		for (String substream_id : substream_ids) {					
		 
			ConcurrentLinkedQueue<Event> events = stream.substreams.get(substream_id);
			computeResults(events);
		}
		System.out.println("Count: " + count);
	}
	
	public void computeResults (ConcurrentLinkedQueue<Event> events) {
		
		// Initiate data structures: stack, last events
		Stack<Event> stack = new Stack<Event>();
		ArrayList<Event> lastEvents = new ArrayList<Event>();
		ArrayList<Event> newLastEvents = new ArrayList<Event>();
		int curr_sec = -1;
		int prev_sec = -1;
		int pointerCount = 0;	
		Event event = events.peek();
		
		int number_of_events_in_prev_sec = 0;
		int number_of_events_in_curr_sec = 0;
				
		while (event != null) {
			
			event = events.poll();
			boolean added = false;	
			int count = (number_of_events_at_second.containsKey(prev_sec)) ? number_of_events_at_second.get(prev_sec) : 0;
			int required_count = (count * query.getPercentage())/100;
			//System.out.println("count in " + prev_sec + " : " + count + " required: " + required_count);
							
			// Store pointers to its predecessors
			if (event.sec == curr_sec) {				
						
				for (Event last : lastEvents) {
					
					if (event.pointers == null) System.out.println("Pointers null");					
					
					if (!event.pointers.contains(last) && event.actual_count<required_count) {
						event.pointers.add(last);
						pointerCount++;	
						event.actual_count++;
						//System.out.println(last.id + " , " + event.id);
				}}
				if (query.event_selection_strategy.equals("any")) {
					newLastEvents.add(event);
					added = true;
					number_of_events_in_curr_sec++;
				}
			} else {
				
				prev_sec = curr_sec;
				curr_sec = event.sec;
				number_of_events_in_prev_sec = number_of_events_in_curr_sec;
				number_of_events_in_curr_sec = 1;
				number_of_events_at_second.put(prev_sec,number_of_events_in_prev_sec);
				//System.out.println(prev_sec + " " + number_of_events_in_prev_sec);								
				
				// Update last events and draw pointers from event to all last events
				lastEvents.clear();
				lastEvents.addAll(newLastEvents);	
				
				for (Event last : lastEvents) {
					if (!event.pointers.contains(last) && !last.marked && event.actual_count<required_count) {
						event.pointers.add(last);
						pointerCount++;
						event.actual_count++;
						//System.out.println(last.id + " , " + event.id);
				}}
				newLastEvents.clear();
				newLastEvents.add(event);
				added = true;				
			}			
			// Store the event in a stack or mark all events as incompatible with all future events
			if (added) {
				stack.add(event);
			} else {
				if (query.event_selection_strategy.equals("cont")) 
					for (Event e : stack) 
						e.marked = true;						
			}
			event = events.peek();
		}	
		number_of_events_at_second.put(curr_sec,number_of_events_in_prev_sec);
		//System.out.println(curr_sec + " " + number_of_events_in_prev_sec);
				
		// For each new last event, traverse the pointers to extract trends
		ArrayList<EventSequence> without_duplicates = new ArrayList<EventSequence>();
		for (Event lastEvent : newLastEvents) 
			if (!lastEvent.marked)
				traversePointers(lastEvent, new Stack<Event>(), without_duplicates);
		
		//System.out.println(without_duplicates);
		count = count.add(new BigInteger(without_duplicates.size()+""));
		total_mem.set(total_mem.get() + stack.size() + pointerCount + without_duplicates.size());
	}
	
	// DFS in the stack
	public void traversePointers (Event event, Stack<Event> current_trend, ArrayList<EventSequence> without_duplicates) {       
			
		current_trend.push(event);
		//System.out.println("pushed " + event.id);
		
		/*** Count all trends with this new event ***/
		ArrayList<Event> input = new ArrayList<Event>();
	    input.addAll(current_trend);
	    without_duplicates = getIncompleteTrends(input,without_duplicates);
	    	    	
	    /*** Traverse the following nodes. ***/
		for(Event previous : event.pointers) {        		
			//System.out.println("following of " + previous.id + " is " + event.id);
	       	traversePointers(previous,current_trend,without_duplicates);        		
	    }        	
	    
	    Event top = current_trend.pop();
	    if (!top.flagged) {
	    	top.flagged = true;
	    	//System.out.println("popped and flagged " + top.id);
	    }  	    	    
	}	
	
	public ArrayList<EventSequence> getIncompleteTrends (ArrayList<Event> elements, ArrayList<EventSequence> without_duplicates) {
		
		// The new event is obligatory in all new trends
	    Event obligatory = elements.remove(elements.size()-1);
	    	    	    
	    // Get the new trends with obligatory event
	    ArrayList<EventSequence> with_duplicates = getCombinations(elements,obligatory);
	      
	    // Eliminate duplicates
	 	for (EventSequence seq : with_duplicates) {
	 		if (!seq.allFlagged() && !without_duplicates.contains(seq)) 
	 			without_duplicates.add(seq);
	 	}	   
	 	//System.out.println(without_duplicates.toString());
	    return without_duplicates;
	}
	
	public ArrayList<EventSequence> getCombinations (ArrayList<Event> elements, Event obligatory) {
		
		ArrayList<EventSequence> with_duplicates = new ArrayList<EventSequence>();
		
	    /*** Base case: Obligatory event is a trend ***/
		if(elements.size() == 0) {
			
			ArrayList<Event> events = new ArrayList<Event>();
			events.add(obligatory);
			EventSequence seq = new EventSequence(events);		
			with_duplicates.add(seq);
						
		} else {
				
			/*** Recursive case: Combine the first event with all combinations of other events ***/
			Event new_event = elements.remove(0);  
			//!!! int id_of_last_predecessor = number_of_events_at_second.get(new_event.sec);
			//!!! int id_of_last_compatible_predecessor = (id_of_last_predecessor * query.getPercentage())/100;
			//System.out.println(new_event.id + " : " + id_of_last_predecessor + ", " + id_of_last_compatible_predecessor);
			
			ArrayList<EventSequence> rest = getCombinations(elements,obligatory);
			int limit = rest.size();
			
			for (int i=0; i<limit; i++) {			
				
				Event prev_event = rest.get(i).events.get(0);	
				//!!! if (prev_event.id > id_of_last_compatible_predecessor) break;
				
				boolean contained_in_pointers = new_event.pointers.contains(prev_event);
				//!!! boolean compatible = query.compatible(prev_event,new_event,id_of_last_compatible_predecessor);
								
				if ((query.event_selection_strategy.equals("any") || contained_in_pointers)) { // !!! && compatible) { 
								
					ArrayList<Event> events = new ArrayList<Event>();
					events.add(new_event);
					events.addAll(rest.get(i).events);		
					EventSequence seq = new EventSequence(events);
					rest.add(seq);
					//System.out.println(prev_event.id + " is predecessor of " + new_event.id);
				}				
			}
			with_duplicates.addAll(rest);
		}		
	    return with_duplicates;
	}	
}
