import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ConfigBed, Patient, PatientService, RiskAssessment } from '../patient.service';
import { environment } from '../../environments/environment';

@Component({
  selector: 'app-patient-card',
  templateUrl: './patient-card.component.html',
  styleUrls: ['./patient-card.component.css']
})
export class PatientCardComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('cardsScroll') cardsScrollRef?: ElementRef<HTMLDivElement>;

  patients: Patient[] = [];
  configBeds: ConfigBed[] = [];
  selectedDepartmentCode = '';
  loading = false;
  errorMessage = '';

  private autoRefreshTimer?: ReturnType<typeof setInterval>;
  private autoScrollRafId?: number;
  private autoScrollResumeTimer?: ReturnType<typeof setTimeout>;
  private autoScrollPaused = false;
  private autoScrollDirection: 1 | -1 = 1;
  private autoScrollLastTimestamp = 0;
  private autoScrollPendingPx = 0;
  private requestSequence = 0;
  private readonly autoRefreshIntervalMs = 30 * 1000;
  private readonly autoScrollSpeedPxPerSecond = 24;
  private readonly autoScrollResumeDelayMs = 20 * 1000;

  private readonly messageHandler = (event: MessageEvent): void => {
    if (!this.isAllowedOrigin(event.origin)) {
      return;
    }
    if (event.data && (event.data as { type?: string }).type === 'HOSPITAL_LOGOUT') {
      this.ngZone.run(() => this.resetToWaiting());
      return;
    }
    const deptCode = this.extractDeptCode(event.data);
    if (!deptCode || deptCode === this.selectedDepartmentCode) {
      return;
    }
    this.stopHandshake();
    this.ngZone.run(() => this.switchDepartment(deptCode));
  };

  private handshakeTimer?: ReturnType<typeof setInterval>;
  private readonly handshakeIntervalMs = 2 * 1000;
  private readonly handshakeMaxRetry = 5;
  private handshakeTried = 0;

  constructor(private patientService: PatientService, private ngZone: NgZone) { }

  ngOnInit(): void {
    window.addEventListener('message', this.messageHandler);
    this.startAutoRefresh();

    // 便于独立调试；正式嵌入时以 postMessage 的科室编码为准。
    const queryDeptCode = new URLSearchParams(window.location.search).get('deptCode');
    if (queryDeptCode) {
      this.switchDepartment(queryDeptCode);
    } else {
      this.startHandshake();
    }
  }

  ngAfterViewInit(): void {
    this.startAutoScroll();
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.messageHandler);
    this.stopAutoRefresh();
    this.stopHandshake();
    this.stopAutoScroll();
    this.clearAutoScrollResumeTimer();
  }

  private resetToWaiting(): void {
    this.stopHandshake();
    this.selectedDepartmentCode = '';
    this.patients = [];
    this.configBeds = [];
    this.errorMessage = '';
    this.loading = false;
    this.startHandshake();
  }

  private startHandshake(): void {
    if (this.selectedDepartmentCode || this.handshakeTimer || window.parent === window) {
      return;
    }
    this.handshakeTried = 0;
    const tryOnce = (): void => {
      if (this.selectedDepartmentCode) {
        this.stopHandshake();
        return;
      }
      this.handshakeTried += 1;
      this.sendReady();
      if (this.handshakeTried >= this.handshakeMaxRetry) {
        this.stopHandshake();
      }
    };
    tryOnce();
    this.handshakeTimer = setInterval(tryOnce, this.handshakeIntervalMs);
  }

  private stopHandshake(): void {
    if (this.handshakeTimer) {
      clearInterval(this.handshakeTimer);
      this.handshakeTimer = undefined;
    }
  }

  private sendReady(): void {
    if (window.parent === window) {
      return;
    }
    // 与质控大屏/打印程序一致：宿主只认 SmartCareReady，收到后回传 SmartCare
    window.parent.postMessage({ type: 'SmartCareReady' }, '*');
    // 兼容旧宿主监听名
    window.parent.postMessage({ type: 'patient-card-ready' }, '*');
  }

  private switchDepartment(deptCode: string): void {
    const normalized = deptCode.trim();
    if (!normalized) {
      return;
    }
    this.selectedDepartmentCode = normalized;
    this.patients = [];
    this.configBeds = [];
    this.errorMessage = '';
    this.loadDepartmentData();
  }

  private loadDepartmentData(): void {
    const deptCode = this.selectedDepartmentCode;
    const sequence = ++this.requestSequence;
    this.loading = true;
    this.patientService.getConfigBedsByDeptCode(deptCode).subscribe({
      next: beds => {
        if (sequence !== this.requestSequence || deptCode !== this.selectedDepartmentCode) {
          return;
        }
        this.configBeds = beds || [];
        this.loadPatients(sequence, deptCode);
      },
      error: () => {
        if (sequence === this.requestSequence) {
          this.configBeds = [];
          this.loadPatients(sequence, deptCode);
        }
      }
    });
  }

  private loadPatients(sequence?: number, deptCode?: string): void {
    const currentDeptCode = deptCode || this.selectedDepartmentCode;
    if (!currentDeptCode) {
      return;
    }
    const currentSequence = sequence === undefined ? ++this.requestSequence : sequence;
    this.loading = true;
    this.patientService.getDashboardPatients(currentDeptCode).subscribe({
      next: patients => {
        if (currentSequence !== this.requestSequence || currentDeptCode !== this.selectedDepartmentCode) {
          return;
        }
        this.renderPatients(patients || []);
        this.loading = false;
        this.errorMessage = '';
      },
      error: () => {
        if (currentSequence === this.requestSequence) {
          this.loading = false;
          this.errorMessage = '患者数据加载失败';
        }
      }
    });
  }

  private renderPatients(patients: Patient[]): void {
    const occupiedBeds: Patient[] = patients.map(patient => {
      const configBed = this.configBeds.find(bed => bed.hisName === patient.hisBed);
      const age = this.calculateAge(patient.birthday);
      return {
        ...patient,
        bedNumber: configBed ? configBed.showName : patient.hisBed || '',
        age,
        avatarSrc: this.getAvatarSrc(age, patient.gender),
        isEmpty: false
      };
    });

    const occupiedBedNames = new Set(patients.map(patient => patient.hisBed));
    const emptyBeds: Patient[] = this.configBeds
      .filter(bed => !occupiedBedNames.has(bed.hisName))
      .map(bed => ({ id: `empty-${bed.id}`, bedNumber: bed.showName, isEmpty: true }));

    this.patients = [...occupiedBeds, ...emptyBeds].sort((a, b) =>
      this.bedSortValue(a.bedNumber) - this.bedSortValue(b.bedNumber));
    setTimeout(() => this.startAutoScroll(), 0);
  }

  riskClass(risk?: RiskAssessment): string {
    return risk ? `risk-${risk.level || 'unknown'}` : 'risk-unknown';
  }

  riskTooltip(title: string, risk?: RiskAssessment): string {
    if (!risk) {
      return `${title}：暂无评估`;
    }
    const parts = [`${title}：${risk.result || '未分级'}`];
    if (risk.score !== undefined && risk.score !== null && risk.score !== '') {
      parts.push(`评分 ${risk.score}`);
    }
    if (risk.assessTime) {
      parts.push(this.formatDateTime(risk.assessTime));
    }
    return parts.join('；');
  }

  getAdmissionDays(admissionDate?: string): number {
    if (!admissionDate) {
      return 1;
    }
    const d = new Date(admissionDate);
    if (Number.isNaN(d.getTime())) {
      return 1;
    }
    const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.floor((startOfDay(new Date()) - startOfDay(d)) / 86400000);
    return Math.max(1, diff + 1);
  }

  formatDateTime(dateString?: string): string {
    if (!dateString) {
      return '';
    }
    const date = new Date(dateString);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    const pad = (value: number): string => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  onCardsMouseEnter(): void {
    this.autoScrollPaused = true;
    this.scheduleAutoScrollResume();
  }

  onCardsMouseMove(): void {
    this.autoScrollPaused = true;
    this.scheduleAutoScrollResume();
  }

  onCardsMouseLeave(): void {
    this.autoScrollPaused = false;
    this.clearAutoScrollResumeTimer();
  }

  private extractDeptCode(rawData: unknown): string | null {
    let data: any = rawData;
    if (typeof data === 'string') {
      const text = data.trim();
      if (!text) {
        return null;
      }
      try {
        data = JSON.parse(text);
      } catch (_) {
        return text;
      }
    }
    if (!data || typeof data !== 'object') {
      return null;
    }
    // 现场标准协议（与质控/打印程序一致）：{type:'SmartCare', patient:{deptCode|deptCode2}}
    if (data.type === 'SmartCare') {
      const patient = data.patient || {};
      const smartCareCode = patient.deptCode || patient.deptCode2;
      return smartCareCode ? String(smartCareCode).trim() : null;
    }
    // 医院上下文：{type:'HOSPITAL_CONTEXT'|'HOSPITAL_CONTEXT_CHANGED', payload:{deptCode}}
    if (data.type === 'HOSPITAL_CONTEXT' || data.type === 'HOSPITAL_CONTEXT_CHANGED') {
      const payload = data.payload || {};
      const contextCode = payload.deptCode || payload.departmentCode || data.deptCode;
      return contextCode ? String(contextCode).trim() : null;
    }
    const value = data.deptCode
      ?? data.departmentCode
      ?? data.dept_code
      ?? data.data?.deptCode
      ?? data.data?.departmentCode
      ?? data.params?.deptCode
      ?? data.patient?.deptCode
      ?? data.patient?.deptCode2
      ?? data.payload?.deptCode
      ?? data.payload?.departmentCode;
    return value === undefined || value === null ? null : String(value).trim();
  }

  private isAllowedOrigin(origin: string): boolean {
    const allowedOrigins: string[] = environment.allowedMessageOrigins || [];
    return allowedOrigins.length === 0 || allowedOrigins.includes(origin);
  }

  private calculateAge(birthday?: string): number {
    if (!birthday) {
      return 0;
    }
    const birthDate = new Date(birthday);
    const today = new Date();
    let age = today.getFullYear() - birthDate.getFullYear();
    const monthDiff = today.getMonth() - birthDate.getMonth();
    if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birthDate.getDate())) {
      age--;
    }
    return Math.max(0, age);
  }

  private getAvatarSrc(age: number, gender?: string): string | undefined {
    const suffix = gender === 'Male' ? 'man' : 'woman';
    if (age === 0) return `assets/baby-${suffix}.png`;
    if (age <= 6) return `assets/toddler-${suffix}.png`;
    if (age <= 14) return `assets/child-${suffix}.png`;
    if (age <= 16) return `assets/teen-${suffix}.png`;
    if (age <= 24) return `assets/young-${suffix}.png`;
    if (age < 60) return `assets/adult-${suffix}.png`;
    return `assets/elderly-${suffix}.png`;
  }

  private bedSortValue(value?: string): number {
    const parsed = parseInt((value || '').replace(/[^0-9]/g, ''), 10);
    return Number.isNaN(parsed) ? Number.MAX_SAFE_INTEGER : parsed;
  }

  private startAutoRefresh(): void {
    this.stopAutoRefresh();
    this.autoRefreshTimer = setInterval(() => {
      if (this.selectedDepartmentCode) {
        this.loadDepartmentData();
      }
    }, this.autoRefreshIntervalMs);
  }

  private stopAutoRefresh(): void {
    if (this.autoRefreshTimer) {
      clearInterval(this.autoRefreshTimer);
      this.autoRefreshTimer = undefined;
    }
  }

  private startAutoScroll(): void {
    this.stopAutoScroll();
    this.autoScrollLastTimestamp = 0;
    this.autoScrollRafId = window.requestAnimationFrame(this.runAutoScrollFrame);
  }

  private stopAutoScroll(): void {
    if (this.autoScrollRafId !== undefined) {
      window.cancelAnimationFrame(this.autoScrollRafId);
      this.autoScrollRafId = undefined;
    }
    this.autoScrollLastTimestamp = 0;
    this.autoScrollPendingPx = 0;
  }

  private scheduleAutoScrollResume(): void {
    this.clearAutoScrollResumeTimer();
    this.autoScrollResumeTimer = setTimeout(() => {
      this.autoScrollPaused = false;
    }, this.autoScrollResumeDelayMs);
  }

  private clearAutoScrollResumeTimer(): void {
    if (this.autoScrollResumeTimer) {
      clearTimeout(this.autoScrollResumeTimer);
      this.autoScrollResumeTimer = undefined;
    }
  }

  private runAutoScrollFrame = (timestamp: number): void => {
    if (this.autoScrollLastTimestamp === 0) {
      this.autoScrollLastTimestamp = timestamp;
    }
    const container = this.cardsScrollRef?.nativeElement;
    const deltaMs = timestamp - this.autoScrollLastTimestamp;
    this.autoScrollLastTimestamp = timestamp;
    if (container && !this.autoScrollPaused) {
      const maxScrollTop = Math.max(container.scrollHeight - container.clientHeight, 0);
      if (maxScrollTop <= 0) {
        container.scrollTop = 0;
      } else {
        this.autoScrollPendingPx += (this.autoScrollSpeedPxPerSecond * deltaMs / 1000) * this.autoScrollDirection;
        const step = this.autoScrollPendingPx > 0 ? Math.floor(this.autoScrollPendingPx) : Math.ceil(this.autoScrollPendingPx);
        this.autoScrollPendingPx -= step;
        const next = container.scrollTop + step;
        if (next >= maxScrollTop) {
          container.scrollTop = maxScrollTop;
          this.autoScrollDirection = -1;
          this.autoScrollPendingPx = 0;
        } else if (next <= 0) {
          container.scrollTop = 0;
          this.autoScrollDirection = 1;
          this.autoScrollPendingPx = 0;
        } else {
          container.scrollTop = next;
        }
      }
    }
    this.autoScrollRafId = window.requestAnimationFrame(this.runAutoScrollFrame);
  };
}
