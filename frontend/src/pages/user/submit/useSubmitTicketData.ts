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
 * The dropdowns on the submit form each need their own fetch, and every parent selection
 * must clear the child's state so the user never sees a stale list. Grouping all of that
 * here keeps SubmitTicketPage focused on submission logic.
 *
 * Scoping rules the hook enforces:
 *   • Projects come from the admin-assigned membership list. If exactly one is returned
 *     it is auto-selected so the user doesn't have to touch the picker at all.
 *   • Departments load from that project (empty when no project is picked yet).
 *   • Subcategories load from the picked department if one is chosen, or from the whole
 *     project when the user leaves the (now optional) department picker empty.
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

  // Fetch the projects the caller is a member of. When the admin assigned exactly one
  // project we lock that in immediately so the form arrives pre-scoped.
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

  // Departments live inside the picked project. Nothing to show until the user (or the
  // auto-select above) has committed to a project.
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

  // Cascade reset: swapping the project scope invalidates every downstream selection.
  useEffect(() => {
    setDepartmentId('');
    setSubcategoryId('');
    setSubcategories([]);
    setFields([]);
  }, [projectId]);

  // Subcategories: prefer department scope when the user picks one, otherwise widen to
  // the whole project so a user with a single subcategory doesn't have to touch the
  // department picker at all.
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

  // A subcategory chosen under the "any department" mode is still valid if the user then
  // picks a department; only reset the child selections when the department itself moves.
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
