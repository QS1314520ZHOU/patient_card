import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

export interface RiskAssessment {
  level: 'low' | 'medium' | 'high' | 'unknown';
  result?: string;
  score?: string;
  assessTime?: string;
  assessName?: string;
}

export interface Patient {
  id: string;
  hisPid?: string;
  mrn?: string;
  name?: string;
  birthday?: string;
  gender?: string;
  hisBed?: string;
  status?: string;
  icuAdmissionTime?: string;
  clinicalDiagnosis?: string;
  deptCode?: string;
  tel?: string;
  bedDoctor?: string;
  admissionType?: string;
  newAdmission?: boolean;
  surgeryAdmission?: boolean;
  transferIn?: boolean;
  vteRisk?: RiskAssessment;
  pipeRisk?: RiskAssessment;
  pressureDamageRisk?: RiskAssessment;
  nutritionRisk?: RiskAssessment;
  bedNumber?: string;
  isEmpty?: boolean;
  age?: number;
  avatarSrc?: string;
}

export interface ConfigBed {
  id: string;
  hisName: string;
  showName: string;
  deptCode: string;
}

@Injectable({ providedIn: 'root' })
export class PatientService {
  private readonly apiUrl = environment.apiUrl + '/api/patients';

  constructor(private http: HttpClient) { }

  getConfigBedsByDeptCode(deptCode: string): Observable<ConfigBed[]> {
    return this.http.get<ConfigBed[]>(`${this.apiUrl}/config-beds/${encodeURIComponent(deptCode)}`);
  }

  getDashboardPatients(deptCode: string): Observable<Patient[]> {
    return this.http.get<Patient[]>(`${this.apiUrl}/dashboard/${encodeURIComponent(deptCode)}`);
  }
}
