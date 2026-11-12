package com.patientcard.patient_card_backend.handover;
import java.time.*;
/** Previous day 08:00 inclusive to today's 08:00 exclusive, in hospital time. */
public final class Window {
 public final LocalDateTime from,to,asOf;
 public Window(Instant instant){asOf=LocalDateTime.ofInstant(instant,ZoneId.of("Asia/Shanghai"));to=asOf.toLocalDate().atTime(8,0);from=to.minusDays(1);}
 public boolean contains(LocalDateTime value){return value!=null&&!value.isBefore(from)&&value.isBefore(to)&&!value.isAfter(asOf);}
}
