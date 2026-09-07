import { useEffect, useState } from 'react';
import { extractError } from '../../../api/client';
import {
  departmentsApi,
  fieldsApi,
  projectsApi,
  subcategoriesApi,
} from '../../../api/resources';
import type {
  CustomField,
  Department,
  Project,
  Subcategory,
} from '../../../api/types';

export interface UseSubmitTicketDataResult {
  projects: Project[];
  departments: Department[];
  subcategories: Subcategory[];
  fields: CustomField[];
  loading: boolean;
  loadError: string | null;
  projectId: string;
  departmentId: string;
  subcategoryId: string;
  setProjectId: (id: string) => void;
  setDepartmentId: (id: string) => void;
  setSubcategoryId: (id: string) => void;
}

export function useSubmitTicketData(): UseSubmitTicketDataResult {
  const [projects, setProjects] = useState<Project[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [subcategories, setSubcategories] = useState<Subcategory[]>([]);
  const [fields, setFields] = useState<CustomField[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [projectId, setProjectId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [subcategoryId, setSubcategoryId] = useState('');

  useEffect(() => {
    const ctrl = new AbortController();
    projectsApi.userList(ctrl.signal)
      .then((list) => {
        setProjects(list);
        if (list.length === 1) setProjectId(String(list[0].id));
      })
      .catch(() => setProjects([]));
    return () => ctrl.abort();
  }, []);

  useEffect(() => {
    if (!projectId) {
      setDepartments([]);
      setLoading(false);
      return;
    }
    const ctrl = new AbortController();
    setLoading(true);
    departmentsApi.userList(Number(projectId), ctrl.signal)
      .then(setDepartments)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); })
      .finally(() => { if (!ctrl.signal.aborted) setLoading(false); });
    return () => ctrl.abort();
  }, [projectId]);

  useEffect(() => {
    setDepartmentId('');
    setSubcategoryId('');
    setSubcategories([]);
    setFields([]);
  }, [projectId]);

  useEffect(() => {
    if (!projectId && !departmentId) {
      setSubcategories([]);
      return;
    }
    const ctrl = new AbortController();
    const filter = departmentId
      ? { departmentId: Number(departmentId) }
      : { projectId: Number(projectId) };
    subcategoriesApi.userList(filter, ctrl.signal)
      .then(setSubcategories)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); });
    return () => ctrl.abort();
  }, [projectId, departmentId]);

  useEffect(() => {
    setSubcategoryId('');
    setFields([]);
  }, [departmentId]);

  useEffect(() => {
    if (!subcategoryId) {
      setFields([]);
      return;
    }
    const ctrl = new AbortController();
    fieldsApi.activeList(Number(subcategoryId), ctrl.signal)
      .then(setFields)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); });
    return () => ctrl.abort();
  }, [subcategoryId]);

  return {
    projects, departments, subcategories, fields,
    loading, loadError,
    projectId, departmentId, subcategoryId,
    setProjectId, setDepartmentId, setSubcategoryId,
  };
}
