package com.patientcard.patient_card_backend.handover;
import java.time.*;
/** Previous day 08:00 inclusive to today's 08:00 exclusive, in hospital time. */
public final class Window {
 public final LocalDateTime from,to,asOf;
 public Window(Instant instant){this(instant,"08:00","08:00");}
 public Window(Instant instant,String start,String end){asOf=LocalDateTime.ofInstant(instant,ZoneId.of("Asia/Shanghai"));LocalTime s=LocalTime.parse(start),e=LocalTime.parse(end);to=asOf.toLocalDate().atTime(e);from=to.toLocalDate().atTime(s).minusDays(s.isBefore(e)?0:1);}
 public boolean contains(LocalDateTime value){return value!=null&&!value.isBefore(from)&&value.isBefore(to)&&!value.isAfter(asOf);}
}
