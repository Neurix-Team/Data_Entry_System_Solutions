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

/**
 * The chained project → department → subcategory → fields dropdowns each need their own
 * fetch, and every parent selection must clear the child's state so the user never sees a
 * stale list. Grouping all of that here keeps SubmitTicketPage focused on submission logic.
 *
 * Every request runs behind an AbortController so a fast dropdown change doesn't let an
 * older, slower response overwrite the newer one.
 */
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
    // Projects are optional on a ticket — silently drop the list if the request fails
    // (e.g. the /api/projects endpoint isn't deployed yet on an older backend).
    const ctrl = new AbortController();
    projectsApi.userList(ctrl.signal).then(setProjects).catch(() => setProjects([]));
    return () => ctrl.abort();
  }, []);

  // Fetch departments whenever project scope changes. AbortController stops a stale
  // response from overwriting a fresher one if the user changes the dropdown quickly.
  useEffect(() => {
    const ctrl = new AbortController();
    const pid = projectId ? Number(projectId) : undefined;
    departmentsApi.userList(pid, ctrl.signal)
      .then(setDepartments)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); })
      .finally(() => { if (!ctrl.signal.aborted) setLoading(false); });
    return () => ctrl.abort();
  }, [projectId]);

  // Cascade reset: swapping the project scope invalidates every downstream selection.
  useEffect(() => {
    setDepartmentId('');
    setSubcategoryId('');
    setSubcategories([]);
    setFields([]);
  }, [projectId]);

  useEffect(() => {
    if (!departmentId) {
      setSubcategories([]);
      return;
    }
    const ctrl = new AbortController();
    subcategoriesApi.userList(Number(departmentId), ctrl.signal)
      .then(setSubcategories)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); });
    return () => ctrl.abort();
  }, [departmentId]);

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
