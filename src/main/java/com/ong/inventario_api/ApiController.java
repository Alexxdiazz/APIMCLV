package com.ong.inventario_api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {
    private final JdbcTemplate jdbc;
    private static final Pattern SAFE = Pattern.compile("[a-z_]+[a-z0-9_]*");
    private static final Map<String,String> TABLES = new LinkedHashMap<>();
    private static final Map<String,String> PK = new HashMap<>();
    static {
        add("articulos","articulo","id_articulo"); add("articulo","articulo","id_articulo");
        add("categorias","categoria","id_categoria"); add("categoria","categoria","id_categoria");
        add("ubicaciones","ubicacion","id_ubicacion"); add("ubicacion","ubicacion","id_ubicacion");
        add("entidades","tercero","id_tercero"); add("terceros","tercero","id_tercero"); add("tercero","tercero","id_tercero");
        add("usuarios","usuario","id_usuario"); add("usuario","usuario","id_usuario");
        add("roles","rol","id_rol"); add("rol","rol","id_rol"); add("usuario_rol","usuario_rol",null);
        add("donaciones","donacion","id_donacion"); add("donacion","donacion","id_donacion");
        add("detalle_donacion","detalle_donacion","id_detalle_donacion");
        add("entregas","entrega","id_entrega"); add("entrega","entrega","id_entrega");
        add("detalle_entrega","detalle_entrega","id_detalle_entrega");
        add("prestamos","prestamo","id_prestamo"); add("prestamo","prestamo","id_prestamo");
        add("detalle_prestamo","detalle_prestamo","id_detalle_prestamo");
        add("devoluciones","devolucion","id_devolucion"); add("devolucion","devolucion","id_devolucion");
        add("detalle_devolucion","detalle_devolucion","id_detalle_devolucion");
        add("reservas","reserva","id_reserva"); add("reserva","reserva","id_reserva");
        add("detalle_reserva","detalle_reserva","id_detalle_reserva");
        add("fotografias","fotografia","id_fotografia"); add("fotografia","fotografia","id_fotografia");
        add("stock","stock","id_stock"); add("movimientos","movimiento","id_movimiento"); add("historial","movimiento","id_movimiento"); add("movimiento","movimiento","id_movimiento");
    }
    private static void add(String route, String table, String pk) { TABLES.put(route,table); if(pk!=null) PK.put(table,pk); }
    public ApiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        sincronizarSecuencias();
    }

    /** Aligns PostgreSQL identity sequences with existing rows after manual/test inserts. */
    private void sincronizarSecuencias() {
        for (Map.Entry<String,String> entry : PK.entrySet()) {
            String table = entry.getKey();
            String pk = entry.getValue();
            try {
                String sequence = jdbc.queryForObject("SELECT pg_get_serial_sequence(?, ?)", String.class, "public." + table, pk);
                if (sequence == null || sequence.isBlank()) continue;
                Long maxId = jdbc.queryForObject("SELECT COALESCE(MAX(" + pk + "),0) FROM " + table, Long.class);
                if (maxId != null && maxId > 0) jdbc.queryForObject("SELECT setval(?::regclass, ?, true)", Long.class, sequence, maxId);
                else jdbc.queryForObject("SELECT setval(?::regclass, 1, false)", Long.class, sequence);
            } catch (Exception ex) {
                // Keep the API available if a custom schema lacks one of the expected sequences.
                System.err.println("No se pudo sincronizar la secuencia de " + table + ": " + ex.getMessage());
            }
        }
    }

    @GetMapping("/salud") public Map<String,Object> salud() { return Map.of("estado","ok","baseDeDatos","mujeres_celebran_la_vida"); }
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String,Object>> login(@RequestBody Map<String,Object> body) {
        String identificador = Objects.toString(body.getOrDefault("email", body.getOrDefault("usuario", body.getOrDefault("username", ""))), "").trim();
        String password = Objects.toString(body.getOrDefault("password", body.getOrDefault("contrasena", body.getOrDefault("passwordHash", ""))), "");
        List<Map<String,Object>> matches = jdbc.query(
            "SELECT u.id_usuario,u.nombre,u.apellido,u.usuario,u.email,u.activo, " +
            "COALESCE((SELECT CASE LOWER(r.nombre) " +
            "WHEN 'administrador' THEN 'ADMINISTRADOR' " +
            "WHEN 'responsable de inventario' THEN 'RESPONSABLE_INVENTARIO' " +
            "WHEN 'usuario operativo' THEN 'USUARIO_OPERATIVO' " +
            "ELSE 'CONSULTA' END " +
            "FROM usuario_rol ur JOIN rol r ON r.id_rol=ur.id_rol " +
            "WHERE ur.id_usuario=u.id_usuario " +
            "ORDER BY CASE LOWER(r.nombre) WHEN 'administrador' THEN 1 WHEN 'responsable de inventario' THEN 2 WHEN 'usuario operativo' THEN 3 ELSE 4 END LIMIT 1), 'CONSULTA') AS rol " +
            "FROM usuario u WHERE (u.usuario=? OR LOWER(COALESCE(u.email,''))=LOWER(?)) AND u.contrasena=? AND u.activo=TRUE",
            (rs,n)->{
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("idUsuario",rs.getInt("id_usuario")); m.put("nombre",rs.getString("nombre"));
                m.put("apellido",rs.getString("apellido")); m.put("usuario",rs.getString("usuario"));
                m.put("email",rs.getString("email")); m.put("activo",rs.getBoolean("activo")); m.put("rol",rs.getString("rol")); return m;
            }, identificador, identificador, password);
        if(matches.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Usuario o contraseña incorrectos");
        return ResponseEntity.ok(matches.get(0));
    }

    @GetMapping("/{recurso}")
    public List<Map<String,Object>> listar(@PathVariable String recurso) {
        String table = table(recurso);
        if (table.equals("articulo")) return listarArticulos();
        if (table.equals("donacion")) return listarDonacionesCompletas();
        if (table.equals("prestamo")) return listarPrestamosCompletos();
        if (table.equals("entrega")) return listarEntregasCompletas();
        if (table.equals("reserva")) return listarReservasCompletas();
        if (table.equals("movimiento")) return listarHistorialCompleto();
        return jdbc.query("SELECT * FROM " + table + " ORDER BY 1", (rs, n) -> transform(rs, table));
    }

    @PostMapping("/prestamos/{id}/devolucion")
    @Transactional
    public ResponseEntity<Map<String,Object>> registrarDevolucion(@PathVariable int id, @RequestBody Map<String,Object> body) {
        Map<String,Object> resultado=registrarDevolucionCompleta(id,body);
        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/reservas/{id}/cancelar")
    @Transactional
    public ResponseEntity<Map<String,Object>> cancelarReserva(@PathVariable int id, @RequestBody Map<String,Object> body) {
        Integer idUsuario=number(body.get("idUsuario"));
        if(idUsuario==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Falta el usuario responsable de cancelar la reserva.");
        Map<String,Object> reserva=jdbc.query("SELECT estado FROM reserva WHERE id_reserva=? FOR UPDATE",(rs,n)->Map.<String,Object>of("estado",rs.getString("estado")),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Reserva no encontrada."));
        String estado=Objects.toString(reserva.get("estado"),"");
        if("CANCELADA".equalsIgnoreCase(estado)||"COMPLETADA".equalsIgnoreCase(estado)) throw new ResponseStatusException(HttpStatus.CONFLICT,"La reserva ya está cancelada o completada.");
        List<Map<String,Object>> detalles=jdbc.query("SELECT id_articulo,cantidad FROM detalle_reserva WHERE id_reserva=?",(rs,n)->Map.of("idArticulo",rs.getInt("id_articulo"),"cantidad",rs.getInt("cantidad")),id);
        for(Map<String,Object> d:detalles) moverStock((Integer)d.get("idArticulo"),(Integer)d.get("cantidad"),"RESERVADO","DISPONIBLE",idUsuario,"RESERVA","Cancelación de reserva #"+id+"; stock liberado");
        jdbc.update("UPDATE reserva SET estado='CANCELADA' WHERE id_reserva=?",id);
        Map<String,Object> out=new LinkedHashMap<>();out.put("idReserva",id);out.put("estado","CANCELADA");out.put("mensaje","Reserva cancelada y stock liberado.");return ResponseEntity.ok(out);
    }

    @PostMapping("/reservas/{id}/completar")
    @Transactional
    public ResponseEntity<Map<String,Object>> completarReserva(@PathVariable int id, @RequestBody Map<String,Object> body) {
        Integer idUsuario = number(body.get("idUsuario"));
        if (idUsuario == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el usuario responsable de realizar la entrega.");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE id_usuario=?", Integer.class, idUsuario) == 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El usuario responsable no existe.");
        Map<String,Object> r = jdbc.query("SELECT id_tercero,id_usuario,estado,observaciones FROM reserva WHERE id_reserva=? FOR UPDATE", (rs,n) -> {
            Map<String,Object> m = new LinkedHashMap<>(); m.put("idTercero",rs.getInt("id_tercero")); m.put("idUsuario",rs.getInt("id_usuario")); m.put("estado",rs.getString("estado")); m.put("observaciones",rs.getString("observaciones")); return m;
        }, id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva no encontrada."));
        String estado = Objects.toString(r.get("estado"), "");
        if (!Set.of("PENDIENTE", "CONFIRMADA").contains(estado.toUpperCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden completar reservas pendientes o confirmadas.");
        List<Map<String,Object>> detalles = jdbc.query("SELECT id_articulo,cantidad,observaciones FROM detalle_reserva WHERE id_reserva=? ORDER BY id_detalle_reserva", (rs,n) -> {
            Map<String,Object> d = new LinkedHashMap<>(); d.put("idArticulo",rs.getInt("id_articulo")); d.put("cantidad",rs.getInt("cantidad")); d.put("observaciones",rs.getString("observaciones")); return d;
        }, id);
        if (detalles.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "La reserva no tiene artículos asociados.");
        Integer idEntrega = jdbc.queryForObject("INSERT INTO entrega(id_tercero,id_usuario,fecha_entrega,motivo,campana,observaciones) VALUES (?,?,?,?,?,?) RETURNING id_entrega", Integer.class,
                r.get("idTercero"), idUsuario, java.sql.Date.valueOf(java.time.LocalDate.now()), "Entrega de reserva #" + id, null, r.get("observaciones"));
        for (Map<String,Object> d : detalles) {
            Integer idArticulo = (Integer)d.get("idArticulo"); Integer cantidad = (Integer)d.get("cantidad");
            moverStock(idArticulo, cantidad, "RESERVADO", "ENTREGADO", idUsuario, "ENTREGA_DEFINITIVA", "Entrega definitiva de reserva #" + id);
            jdbc.update("INSERT INTO detalle_entrega(id_entrega,id_articulo,cantidad,observaciones) VALUES (?,?,?,?)", idEntrega, idArticulo, cantidad, d.get("observaciones"));
        }
        jdbc.update("UPDATE reserva SET estado='COMPLETADA' WHERE id_reserva=?", id);
        Map<String,Object> out = new LinkedHashMap<>(); out.put("idReserva", id); out.put("idEntrega", idEntrega); out.put("estado", "COMPLETADA"); out.put("mensaje", "Reserva entregada; stock, entrega e historial actualizados.");
        return ResponseEntity.ok(out);
    }

    @GetMapping("/articulos/{id}/historial")
    public List<Map<String,Object>> historialDeArticulo(@PathVariable int id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM articulo WHERE id_articulo=?", Integer.class, id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artículo no encontrado.");
        return listarHistorialCompleto().stream().filter(m -> {
            Object a = m.get("articulo");
            return a instanceof Map<?,?> map && Objects.equals(number(map.get("idArticulo")), id);
        }).toList();
    }

    @GetMapping("/{recurso}/{id}")
    public Map<String,Object> obtener(@PathVariable String recurso, @PathVariable int id) {
        String table = table(recurso);
        if (table.equals("articulo")) return listarArticulos().stream().filter(x -> Objects.equals(number(x.get("idArticulo")), id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"No existe el artículo"));
        String pk = PK.get(table); if(pk==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Esta tabla no tiene identificador simple para esta operación");
        return jdbc.query("SELECT * FROM " + table + " WHERE " + pk + "=?", (rs,n)->transform(rs,table), id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Registro no encontrado"));
    }

    @PostMapping("/{recurso}") @Transactional
    public ResponseEntity<Map<String,Object>> crear(@PathVariable String recurso, @RequestBody Map<String,Object> body) {
        String table=table(recurso);
        if(table.equals("articulo")) return ResponseEntity.status(201).body(crearArticulo(body));
        if(table.equals("donacion") && body.containsKey("articulos")) return ResponseEntity.status(201).body(crearDonacionCompleta(body));
        if(table.equals("prestamo") && body.get("articulo") instanceof Map<?,?>) return ResponseEntity.status(201).body(crearPrestamoCompleto(body));
        if(table.equals("entrega") && body.get("articulo") instanceof Map<?,?>) return ResponseEntity.status(201).body(crearEntregaCompleta(body));
        if(table.equals("reserva") && body.get("articulo") instanceof Map<?,?>) return ResponseEntity.status(201).body(crearReservaCompleta(body));
        Map<String,Object> data=toColumns(body,table,false);
        if(data.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No se recibieron columnas válidas para la tabla " + table);
        String cols=String.join(",",data.keySet()); String marks=String.join(",",Collections.nCopies(data.size(),"?"));
        String sql="INSERT INTO "+table+" ("+cols+") VALUES ("+marks+")";
        String pk=PK.get(table);
        if(pk!=null) sql += " RETURNING " + pk;
        Object[] values=data.values().toArray();
        try {
            if(pk!=null) { Number id=jdbc.queryForObject(sql, Number.class, values); return ResponseEntity.status(201).body(obtener(recurso,id.intValue())); }
            jdbc.update(sql,values); return ResponseEntity.status(201).body(data);
        } catch(DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"No se pudo guardar: revisá claves relacionadas y campos obligatorios de la base de datos."); }
    }

    @PutMapping("/{recurso}/{id}") @Transactional
    public Map<String,Object> actualizar(@PathVariable String recurso,@PathVariable int id,@RequestBody Map<String,Object> body) {
        String table=table(recurso);
        if(table.equals("articulo")) return actualizarArticulo(id,body);
        String pk=PK.get(table); if(pk==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Esta tabla no admite actualización por ID");
        Map<String,Object> data=toColumns(body,table,true); data.remove(pk);
        if(data.isEmpty()) return obtener(recurso,id);
        String sets=String.join(",",data.keySet().stream().map(k->k+"=?").toList()); List<Object> args=new ArrayList<>(data.values()); args.add(id);
        int changed=jdbc.update("UPDATE "+table+" SET "+sets+" WHERE "+pk+"=?",args.toArray());
        if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Registro no encontrado");
        return obtener(recurso,id);
    }

    @DeleteMapping("/{recurso}/{id}") @Transactional
    public ResponseEntity<Void> borrar(@PathVariable String recurso,@PathVariable int id) {
        String table=table(recurso);
        if(Set.of("donacion","prestamo","entrega","reserva","devolucion","movimiento").contains(table)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Los registros operativos e históricos no se eliminan para preservar la trazabilidad.");
        if(table.equals("articulo")) { jdbc.update("DELETE FROM fotografia WHERE id_articulo=?",id); jdbc.update("DELETE FROM stock WHERE id_articulo=?",id); table="articulo"; }
        String pk=PK.get(table); if(pk==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Esta tabla no admite eliminación por ID");
        try { int n=jdbc.update("DELETE FROM "+table+" WHERE "+pk+"=?",id); if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Registro no encontrado"); return ResponseEntity.noContent().build(); }
        catch(DataIntegrityViolationException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"No se puede eliminar porque otros registros dependen de este dato."); }
    }

    private Map<String,Object> crearDonacionCompleta(Map<String,Object> body) {
        Object rawArticulos=body.get("articulos");
        if (!(rawArticulos instanceof List<?> lineas) || lineas.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La donación debe incluir al menos un artículo.");
        Integer idUsuario=number(body.get("idUsuario"));
        if(idUsuario==null) idUsuario=number(body.get("id_usuario"));
        if(idUsuario==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Seleccioná el usuario responsable de recibir la donación.");

        Integer idTercero=number(body.get("idTercero"));
        if(idTercero==null) idTercero=number(body.get("id_tercero"));
        Object nuevoRaw=body.get("nuevoDonante");
        if(idTercero==null && nuevoRaw instanceof Map<?,?> nuevo) {
            String nombre=Objects.toString(nuevo.get("nombre"),"").trim();
            String tipo=Objects.toString(nuevo.get("tipoTercero") == null ? "PERSONA" : nuevo.get("tipoTercero"),"PERSONA").trim().toUpperCase(Locale.ROOT);
            if(nombre.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El nombre del nuevo donante es obligatorio.");
            if(!Set.of("PERSONA","FAMILIA","INSTITUCION","ORGANIZACION").contains(tipo)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El tipo de donante no es válido.");
            idTercero=jdbc.queryForObject("INSERT INTO tercero(nombre,tipo_tercero,documento,telefono,direccion,observaciones) VALUES (?,?,?,?,?,?) RETURNING id_tercero",Integer.class,
                    nombre,tipo,blankToNull(nuevo.get("documento")),blankToNull(nuevo.get("telefono")),blankToNull(nuevo.get("direccion")),blankToNull(nuevo.get("observaciones")));
        }
        if(idTercero==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Seleccioná un donante existente o completá los datos del nuevo donante.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tercero WHERE id_tercero=?",Integer.class,idTercero)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El donante seleccionado no existe.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE id_usuario=?",Integer.class,idUsuario)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El usuario responsable no existe.");

        Object fecha=body.getOrDefault("fechaDonacion",java.time.LocalDate.now().toString());
        java.sql.Date fechaSql;
        try { fechaSql=java.sql.Date.valueOf(Objects.toString(fecha).substring(0,10)); }
        catch(Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La fecha de donación no es válida."); }
        Integer idDonacion=jdbc.queryForObject("INSERT INTO donacion(id_tercero,id_usuario,fecha_donacion,tipo_donacion,observaciones) VALUES (?,?,?,?,?) RETURNING id_donacion",Integer.class,
                idTercero,idUsuario,fechaSql,"ARTICULOS",blankToNull(body.get("observaciones")));

        int cantidadLineas=0;
        for(Object raw:lineas) {
            if(!(raw instanceof Map<?,?> item)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Hay una línea de artículo inválida.");
            String codigo=Objects.toString(item.get("codigoInventario"),"").trim();
            String descripcion=Objects.toString(item.get("descripcion"),"").trim();
            Integer idCategoria=number(item.get("idCategoria")); Integer idUbicacion=number(item.get("idUbicacion"));
            Integer cantidad=number(item.get("cantidad"));
            String tipoControl=Objects.toString(item.get("tipoControl"),"CANTIDAD").trim().toUpperCase(Locale.ROOT);
            String condicion=Objects.toString(item.get("condicion"),"BUENO").trim().toUpperCase(Locale.ROOT);
            if(codigo.isBlank()||descripcion.isBlank()||idCategoria==null||idUbicacion==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cada artículo necesita código, descripción, categoría y ubicación.");
            if(cantidad==null||cantidad<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cantidad de cada artículo debe ser mayor que cero.");
            if(!Set.of("INDIVIDUAL","CANTIDAD").contains(tipoControl)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de control inválido para el artículo " + codigo);
            if("INDIVIDUAL".equals(tipoControl)&&cantidad!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El artículo individual " + codigo + " debe tener cantidad 1.");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM articulo WHERE codigo_inventario=?",Integer.class,codigo)>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya existe un artículo con el código " + codigo + ".");
            Integer idArticulo=jdbc.queryForObject("INSERT INTO articulo(id_categoria,id_ubicacion,codigo_inventario,descripcion,marca,modelo,numero_serie,condicion,fecha_ingreso,observaciones,tipo_control,stock_minimo) VALUES (?,?,?,?,?,?,?,?,?,?,?,0) RETURNING id_articulo",Integer.class,
                    idCategoria,idUbicacion,codigo,descripcion,blankToNull(item.get("marca")),blankToNull(item.get("modelo")),blankToNull(item.get("numeroSerie")),condicion,fechaSql,blankToNull(item.get("observaciones")),tipoControl);
            jdbc.update("INSERT INTO detalle_donacion(id_donacion,id_articulo,cantidad,condicion,observaciones) VALUES (?,?,?,?,?)",idDonacion,idArticulo,cantidad,condicion,blankToNull(item.get("observaciones")));
            jdbc.update("INSERT INTO stock(id_articulo,cantidad,estado) VALUES (?,?,?)",idArticulo,cantidad,"DISPONIBLE");
            jdbc.update("INSERT INTO movimiento(id_articulo,id_usuario,tipo_movimiento,cantidad,estado_anterior,estado_nuevo,observaciones) VALUES (?,?,?,?,?,?,?)",idArticulo,idUsuario,"INGRESO_DONACION",cantidad,null,"DISPONIBLE","Donación #"+idDonacion+" recibida de tercero #"+idTercero);
            cantidadLineas++;
        }
        Map<String,Object> respuesta=new LinkedHashMap<>();respuesta.put("idDonacion",idDonacion);respuesta.put("idTercero",idTercero);respuesta.put("idUsuario",idUsuario);respuesta.put("fechaDonacion",fechaSql.toString());respuesta.put("observaciones",body.get("observaciones"));respuesta.put("cantidadLineas",cantidadLineas);respuesta.put("mensaje","Donación registrada con artículos, stock y movimientos.");return respuesta;
    }

    private Map<String,Object> crearPrestamoCompleto(Map<String,Object> body) {
        Integer idTercero=nestedId(body,"receptor","idEntidad","idTercero");
        Integer idUsuario=nestedId(body,"responsable","idUsuario");
        Integer idAutorizador=nestedId(body,"autorizador","idUsuario");
        if(idAutorizador==null) idAutorizador=idUsuario;
        Integer idArticulo=nestedId(body,"articulo","idArticulo"); Integer cantidad=number(body.get("cantidad"));
        if(idTercero==null||idUsuario==null||idArticulo==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El préstamo necesita destinatario, artículo y responsable.");
        if(cantidad==null||cantidad<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cantidad del préstamo debe ser mayor que cero.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tercero WHERE id_tercero=?",Integer.class,idTercero)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El destinatario no existe.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE id_usuario=?",Integer.class,idUsuario)==0||jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE id_usuario=?",Integer.class,idAutorizador)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El responsable o autorizador no existe.");
        java.time.LocalDate prevista=parseDate(body.get("fechaPrevistaDevolucion"),java.time.LocalDate.now().plusDays(7));
        moverStock(idArticulo,cantidad,"DISPONIBLE","PRESTADO",idUsuario,"SALIDA_PRESTAMO","Préstamo al tercero #"+idTercero);
        Integer id=jdbc.queryForObject("INSERT INTO prestamo(id_tercero,id_usuario_registra,id_usuario_autoriza,fecha_prestamo,fecha_devolucion_prevista,estado,observaciones) VALUES (?,?,?,?,?,'ACTIVO',?) RETURNING id_prestamo",Integer.class,idTercero,idUsuario,idAutorizador,java.sql.Date.valueOf(java.time.LocalDate.now()),java.sql.Date.valueOf(prevista),blankToNull(body.get("observaciones")));
        jdbc.update("INSERT INTO detalle_prestamo(id_prestamo,id_articulo,cantidad,observaciones) VALUES (?,?,?,?)",id,idArticulo,cantidad,blankToNull(body.get("observaciones")));
        Map<String,Object> out=new LinkedHashMap<>();out.put("idPrestamo",id);out.put("estado","ACTIVO");out.put("idTercero",idTercero);out.put("idArticulo",idArticulo);out.put("cantidad",cantidad);out.put("fechaPrevistaDevolucion",prevista.toString());return out;
    }

    private Map<String,Object> crearEntregaCompleta(Map<String,Object> body) {
        Integer idTercero=nestedId(body,"receptor","idEntidad","idTercero"); Integer idUsuario=nestedId(body,"responsable","idUsuario");
        Integer idArticulo=nestedId(body,"articulo","idArticulo"); Integer cantidad=number(body.get("cantidad"));
        if(idTercero==null||idUsuario==null||idArticulo==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La entrega necesita destinatario, artículo y responsable.");
        if(cantidad==null||cantidad<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cantidad de la entrega debe ser mayor que cero.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tercero WHERE id_tercero=?",Integer.class,idTercero)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El receptor seleccionado no existe.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE id_usuario=?",Integer.class,idUsuario)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El responsable seleccionado no existe.");
        moverStock(idArticulo,cantidad,"DISPONIBLE","ENTREGADO",idUsuario,"ENTREGA_DEFINITIVA","Entrega definitiva al tercero #"+idTercero);
        Integer id=jdbc.queryForObject("INSERT INTO entrega(id_tercero,id_usuario,fecha_entrega,motivo,campana,observaciones) VALUES (?,?,?,?,?,?) RETURNING id_entrega",Integer.class,idTercero,idUsuario,java.sql.Date.valueOf(java.time.LocalDate.now()),blankToNull(body.get("motivoCampana")),null,blankToNull(body.get("observaciones")));
        jdbc.update("INSERT INTO detalle_entrega(id_entrega,id_articulo,cantidad,observaciones) VALUES (?,?,?,?)",id,idArticulo,cantidad,blankToNull(body.get("observaciones")));
        Map<String,Object> out=new LinkedHashMap<>();out.put("idEntrega",id);out.put("idTercero",idTercero);out.put("idArticulo",idArticulo);out.put("cantidad",cantidad);out.put("fechaEntrega",java.time.LocalDate.now().toString());return out;
    }

    private Map<String,Object> crearReservaCompleta(Map<String,Object> body) {
        Integer idTercero=nestedId(body,"receptor","idEntidad","idTercero"); Integer idUsuario=nestedId(body,"responsable","idUsuario");
        Integer idArticulo=nestedId(body,"articulo","idArticulo"); Integer cantidad=number(body.get("cantidad"));
        if(idTercero==null||idUsuario==null||idArticulo==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La reserva necesita destinatario, artículo y responsable.");
        if(cantidad==null||cantidad<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cantidad reservada debe ser mayor que cero.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tercero WHERE id_tercero=?",Integer.class,idTercero)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El destinatario seleccionado no existe.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE id_usuario=?",Integer.class,idUsuario)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El responsable seleccionado no existe.");
        String estado=Objects.toString(body.getOrDefault("estado","PENDIENTE"),"PENDIENTE").toUpperCase(Locale.ROOT);
        if(!Set.of("PENDIENTE","CONFIRMADA").contains(estado)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Una reserva nueva debe comenzar como PENDIENTE o CONFIRMADA. Para cancelarla o completarla, usá el flujo de la reserva existente.");
        // A reservation earmarks stock: total stock is retained, but it is no longer available to another operation.
        moverStock(idArticulo,cantidad,"DISPONIBLE","RESERVADO",idUsuario,"RESERVA","Reserva para el tercero #"+idTercero);
        Integer id=jdbc.queryForObject("INSERT INTO reserva(id_tercero,id_usuario,fecha_reserva,fecha_vencimiento,estado,observaciones) VALUES (?,?,?,?,?,?) RETURNING id_reserva",Integer.class,idTercero,idUsuario,java.sql.Date.valueOf(java.time.LocalDate.now()),dateOrNull(body.get("fechaPrevistaEntrega")),estado,blankToNull(body.get("observaciones")));
        jdbc.update("INSERT INTO detalle_reserva(id_reserva,id_articulo,cantidad,observaciones) VALUES (?,?,?,?)",id,idArticulo,cantidad,blankToNull(body.get("observaciones")));
        Map<String,Object> out=new LinkedHashMap<>();out.put("idReserva",id);out.put("idTercero",idTercero);out.put("idArticulo",idArticulo);out.put("cantidad",cantidad);out.put("estado",estado);return out;
    }

    private Map<String,Object> registrarDevolucionCompleta(int idPrestamo,Map<String,Object> body) {
        Map<String,Object> prestamo=jdbc.query("SELECT id_prestamo,id_tercero,estado FROM prestamo WHERE id_prestamo=? FOR UPDATE",(rs,n)->{Map<String,Object> m=new HashMap<>();m.put("idTercero",rs.getInt("id_tercero"));m.put("estado",rs.getString("estado"));return m;},idPrestamo).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Préstamo no encontrado."));
        if("DEVUELTO".equalsIgnoreCase(Objects.toString(prestamo.get("estado"),""))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Este préstamo ya figura como devuelto.");
        Integer idUsuario=nestedId(body,"responsableRecepcionDevolucion","idUsuario"); if(idUsuario==null) idUsuario=number(body.get("idUsuario"));
        if(idUsuario==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Seleccioná quién recibe la devolución.");
        Integer idDetalle=jdbc.queryForObject("SELECT id_detalle_prestamo FROM detalle_prestamo WHERE id_prestamo=? ORDER BY id_detalle_prestamo LIMIT 1",Integer.class,idPrestamo);
        Map<String,Object> det=jdbc.queryForObject("SELECT id_articulo,cantidad FROM detalle_prestamo WHERE id_detalle_prestamo=?",(rs,n)->Map.of("idArticulo",rs.getInt("id_articulo"),"cantidad",rs.getInt("cantidad")),idDetalle);
        Integer idArticulo=(Integer)det.get("idArticulo"),cantidad=(Integer)det.get("cantidad");
        String condicion=Objects.toString(body.getOrDefault("estadoDevolucion","BUENO"),"BUENO").toUpperCase(Locale.ROOT);
        Integer idDevolucion=jdbc.queryForObject("INSERT INTO devolucion(id_prestamo,id_tercero,id_usuario,fecha_devolucion,observaciones) VALUES (?,?,?,?,?) RETURNING id_devolucion",Integer.class,idPrestamo,prestamo.get("idTercero"),idUsuario,java.sql.Date.valueOf(java.time.LocalDate.now()),blankToNull(body.get("observaciones")));
        jdbc.update("INSERT INTO detalle_devolucion(id_devolucion,id_detalle_prestamo,cantidad,condicion,observaciones) VALUES (?,?,?,?,?)",idDevolucion,idDetalle,cantidad,condicion,blankToNull(body.get("observaciones")));
        moverStock(idArticulo,cantidad,"PRESTADO","DISPONIBLE",idUsuario,"DEVOLUCION","Devolución del préstamo #"+idPrestamo+"; condición: "+condicion);
        jdbc.update("UPDATE prestamo SET estado='DEVUELTO' WHERE id_prestamo=?",idPrestamo);
        Map<String,Object> out=new LinkedHashMap<>();out.put("idPrestamo",idPrestamo);out.put("idDevolucion",idDevolucion);out.put("estado","DEVUELTO");out.put("fechaRealDevolucion",java.time.LocalDate.now().atStartOfDay().toString());return out;
    }

    private void moverStock(Integer idArticulo,int cantidad,String origen,String destino,Integer idUsuario,String tipoMovimiento,String observaciones) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM articulo WHERE id_articulo=?",Integer.class,idArticulo)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El artículo seleccionado no existe.");
        List<Map<String,Object>> filas=jdbc.query("SELECT id_stock,cantidad FROM stock WHERE id_articulo=? AND estado=? AND cantidad>0 ORDER BY id_stock FOR UPDATE",(rs,n)->{Map<String,Object> m=new HashMap<>();m.put("id",rs.getInt("id_stock"));m.put("cantidad",rs.getInt("cantidad"));return m;},idArticulo,origen);
        int disponible=filas.stream().mapToInt(x->(Integer)x.get("cantidad")).sum();
        if(disponible<cantidad) throw new ResponseStatusException(HttpStatus.CONFLICT,"Stock insuficiente. Disponible: "+disponible+", solicitado: "+cantidad+".");
        int restante=cantidad;
        for(Map<String,Object> f:filas) { if(restante<=0) break; int actual=(Integer)f.get("cantidad"); int tomar=Math.min(actual,restante); jdbc.update("UPDATE stock SET cantidad=cantidad-? WHERE id_stock=?",tomar,f.get("id")); restante-=tomar; }
        jdbc.update("INSERT INTO stock(id_articulo,cantidad,estado) VALUES (?,?,?) " +
                "ON CONFLICT (id_articulo, estado) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad",
                idArticulo,cantidad,destino);
        jdbc.update("INSERT INTO movimiento(id_articulo,id_usuario,tipo_movimiento,cantidad,estado_anterior,estado_nuevo,observaciones) VALUES (?,?,?,?,?,?,?)",idArticulo,idUsuario,tipoMovimiento,cantidad,origen,destino,observaciones);
    }

    private Integer nestedId(Map<String,Object> body,String field,String... candidates) {
        Object value=body.get(field);
        if(value instanceof Map<?,?> m) {
            for(String k:candidates) { Object v=m.get(k); if(v instanceof Number n) return n.intValue(); }
        }
        if(value instanceof Number n) return n.intValue();
        // Also accept normalized flat payloads from older desktop builds.
        for(String candidate:candidates) {
            Object flat=body.get(candidate);
            if(flat instanceof Number n) return n.intValue();
            String snake=candidate.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
            flat=body.get(snake);
            if(flat instanceof Number n) return n.intValue();
        }
        return null;
    }
    private java.time.LocalDate parseDate(Object value,java.time.LocalDate fallback) { if(value==null||value.toString().isBlank()) return fallback; try { return java.time.LocalDate.parse(value.toString().substring(0,10)); } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La fecha indicada no es válida."); } }
    private java.sql.Date dateOrNull(Object value) { if(value==null||value.toString().isBlank()) return null; return java.sql.Date.valueOf(parseDate(value,null)); }

    private List<Map<String,Object>> listarPrestamosCompletos() {
        String sql="SELECT p.id_prestamo,p.fecha_prestamo,p.fecha_devolucion_prevista,p.estado,p.observaciones, t.id_tercero,t.nombre AS tercero_nombre,t.tipo_tercero,t.documento,t.telefono,t.direccion, u.id_usuario,u.nombre AS usuario_nombre,u.email, a.id_articulo,a.codigo_inventario,a.descripcion,a.id_categoria,a.id_ubicacion,a.condicion,a.tipo_control,dp.cantidad, (SELECT MAX(d.fecha_devolucion) FROM devolucion d WHERE d.id_prestamo=p.id_prestamo) AS fecha_real FROM prestamo p JOIN tercero t ON t.id_tercero=p.id_tercero JOIN usuario u ON u.id_usuario=p.id_usuario_registra LEFT JOIN LATERAL (SELECT * FROM detalle_prestamo WHERE id_prestamo=p.id_prestamo ORDER BY id_detalle_prestamo LIMIT 1) dp ON TRUE LEFT JOIN articulo a ON a.id_articulo=dp.id_articulo ORDER BY p.id_prestamo DESC";
        return jdbc.query(sql,(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("idPrestamo",rs.getInt("id_prestamo"));m.put("fechaPrevistaDevolucion",rs.getDate("fecha_devolucion_prevista") == null?null:rs.getDate("fecha_devolucion_prevista").toLocalDate().toString());m.put("fechaRealDevolucion",rs.getDate("fecha_real")==null?null:rs.getDate("fecha_real").toLocalDate().atStartOfDay().toString());m.put("estado",rs.getString("estado"));m.put("observaciones",rs.getString("observaciones"));m.put("cantidad",rs.getInt("cantidad"));m.put("receptor",terceroMap(rs));m.put("responsable",usuarioMap(rs));m.put("articulo",articuloMap(rs));return m;});
    }
    private List<Map<String,Object>> listarEntregasCompletas() {
        String sql="SELECT e.id_entrega,e.fecha_entrega,e.motivo,e.campana,e.observaciones,t.id_tercero,t.nombre AS tercero_nombre,t.tipo_tercero,t.documento,t.telefono,t.direccion,u.id_usuario,u.nombre AS usuario_nombre,u.email,a.id_articulo,a.codigo_inventario,a.descripcion,a.id_categoria,a.id_ubicacion,a.condicion,a.tipo_control,de.cantidad FROM entrega e JOIN tercero t ON t.id_tercero=e.id_tercero JOIN usuario u ON u.id_usuario=e.id_usuario LEFT JOIN LATERAL (SELECT * FROM detalle_entrega WHERE id_entrega=e.id_entrega ORDER BY id_detalle_entrega LIMIT 1) de ON TRUE LEFT JOIN articulo a ON a.id_articulo=de.id_articulo ORDER BY e.id_entrega DESC";
        return jdbc.query(sql,(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("idEntrega",rs.getInt("id_entrega"));m.put("fechaEntrega",rs.getDate("fecha_entrega").toLocalDate().atStartOfDay().toString());m.put("motivoCampana",rs.getString("motivo"));m.put("observaciones",rs.getString("observaciones"));m.put("cantidad",rs.getInt("cantidad"));m.put("receptor",terceroMap(rs));m.put("responsable",usuarioMap(rs));m.put("articulo",articuloMap(rs));return m;});
    }
    private List<Map<String,Object>> listarReservasCompletas() {
        String sql="SELECT r.id_reserva,r.fecha_reserva,r.fecha_vencimiento,r.estado,r.observaciones,t.id_tercero,t.nombre AS tercero_nombre,t.tipo_tercero,t.documento,t.telefono,t.direccion,u.id_usuario,u.nombre AS usuario_nombre,u.email,a.id_articulo,a.codigo_inventario,a.descripcion,a.id_categoria,a.id_ubicacion,a.condicion,a.tipo_control,dr.cantidad FROM reserva r JOIN tercero t ON t.id_tercero=r.id_tercero JOIN usuario u ON u.id_usuario=r.id_usuario LEFT JOIN LATERAL (SELECT * FROM detalle_reserva WHERE id_reserva=r.id_reserva ORDER BY id_detalle_reserva LIMIT 1) dr ON TRUE LEFT JOIN articulo a ON a.id_articulo=dr.id_articulo ORDER BY r.id_reserva DESC";
        return jdbc.query(sql,(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("idReserva",rs.getInt("id_reserva"));m.put("fechaReserva",rs.getDate("fecha_reserva").toLocalDate().atStartOfDay().toString());m.put("fechaPrevistaEntrega",rs.getDate("fecha_vencimiento")==null?null:rs.getDate("fecha_vencimiento").toLocalDate().toString());m.put("estado",rs.getString("estado"));m.put("observaciones",rs.getString("observaciones"));m.put("cantidad",rs.getInt("cantidad"));m.put("receptor",terceroMap(rs));m.put("responsable",usuarioMap(rs));m.put("articulo",articuloMap(rs));return m;});
    }
    private Map<String,Object> terceroMap(ResultSet rs) throws SQLException { Map<String,Object> m=new LinkedHashMap<>();m.put("idEntidad",rs.getInt("id_tercero"));m.put("idTercero",rs.getInt("id_tercero"));m.put("nombreCompleto",rs.getString("tercero_nombre"));m.put("nombre",rs.getString("tercero_nombre"));m.put("tipo",rs.getString("tipo_tercero"));m.put("documentoIdentidad",rs.getString("documento"));m.put("telefono",rs.getString("telefono"));m.put("direccion",rs.getString("direccion"));return m; }
    private Map<String,Object> usuarioMap(ResultSet rs) throws SQLException { Map<String,Object> m=new LinkedHashMap<>();m.put("idUsuario",rs.getInt("id_usuario"));m.put("nombre",rs.getString("usuario_nombre"));m.put("email",rs.getString("email"));return m; }
    private Map<String,Object> articuloMap(ResultSet rs) throws SQLException { if(rs.getObject("id_articulo")==null)return null;Map<String,Object> m=new LinkedHashMap<>();m.put("idArticulo",rs.getInt("id_articulo"));m.put("codigoInventario",rs.getString("codigo_inventario"));m.put("nombre",rs.getString("descripcion"));m.put("descripcion",rs.getString("descripcion"));m.put("idCategoria",rs.getInt("id_categoria"));m.put("idUbicacion",rs.getInt("id_ubicacion"));m.put("estadoConservacion",rs.getString("condicion"));m.put("tipoControl",rs.getString("tipo_control"));return m; }

    private List<Map<String,Object>> listarHistorialCompleto() {
        String sql = "SELECT m.id_movimiento,m.id_articulo,m.id_usuario,m.fecha_movimiento,m.tipo_movimiento,m.cantidad,m.estado_anterior,m.estado_nuevo,m.observaciones, " +
                "a.codigo_inventario,a.descripcion,u.nombre AS usuario_nombre,u.apellido AS usuario_apellido,u.usuario AS username " +
                "FROM movimiento m JOIN articulo a ON a.id_articulo=m.id_articulo JOIN usuario u ON u.id_usuario=m.id_usuario " +
                "ORDER BY m.fecha_movimiento DESC,m.id_movimiento DESC";
        return jdbc.query(sql,(rs,n)->{
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("idMovimiento",rs.getInt("id_movimiento"));
            m.put("fechaRegistro",rs.getTimestamp("fecha_movimiento")==null?null:rs.getTimestamp("fecha_movimiento").toLocalDateTime().toString());
            m.put("tipoOperacion",rs.getString("tipo_movimiento"));
            m.put("estadoAnterior",rs.getString("estado_anterior"));
            m.put("estadoNuevo",rs.getString("estado_nuevo"));
            m.put("descripcionMovimiento",rs.getString("observaciones"));
            m.put("cantidad",rs.getInt("cantidad"));
            Map<String,Object> articulo = new LinkedHashMap<>();
            articulo.put("idArticulo",rs.getInt("id_articulo"));
            articulo.put("codigoInventario",rs.getString("codigo_inventario"));
            articulo.put("nombre",rs.getString("descripcion"));
            articulo.put("descripcion",rs.getString("descripcion"));
            m.put("articulo",articulo);
            Map<String,Object> usuario = new LinkedHashMap<>();
            usuario.put("idUsuario",rs.getInt("id_usuario"));
            usuario.put("nombre",rs.getString("usuario_nombre"));
            usuario.put("apellido",rs.getString("usuario_apellido"));
            usuario.put("usuario",rs.getString("username"));
            m.put("usuario",usuario);
            return m;
        });
    }

    private List<Map<String,Object>> listarDonacionesCompletas() {
        String sql="SELECT d.id_donacion,d.id_tercero,d.id_usuario,d.fecha_donacion,d.observaciones,t.nombre,t.tipo_tercero,t.documento,t.telefono,t.direccion,u.nombre AS usuario_nombre,u.email AS usuario_email FROM donacion d JOIN tercero t ON t.id_tercero=d.id_tercero JOIN usuario u ON u.id_usuario=d.id_usuario ORDER BY d.id_donacion";
        return jdbc.query(sql,(rs,n)->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("idDonacion",rs.getInt("id_donacion"));m.put("fechaRecepcion",rs.getDate("fecha_donacion").toLocalDate().atStartOfDay().toString());m.put("observaciones",rs.getString("observaciones"));
            Map<String,Object> t=new LinkedHashMap<>();t.put("idEntidad",rs.getInt("id_tercero"));t.put("idTercero",rs.getInt("id_tercero"));t.put("nombreCompleto",rs.getString("nombre"));t.put("nombre",rs.getString("nombre"));t.put("tipo",rs.getString("tipo_tercero"));t.put("documentoIdentidad",rs.getString("documento"));t.put("telefono",rs.getString("telefono"));t.put("direccion",rs.getString("direccion"));m.put("donante",t);
            Map<String,Object> u=new LinkedHashMap<>();u.put("idUsuario",rs.getInt("id_usuario"));u.put("nombre",rs.getString("usuario_nombre"));u.put("email",rs.getString("usuario_email"));m.put("responsableRecepcion",u);return m;
        });
    }
    private Object blankToNull(Object value) { if(value==null) return null; String s=value.toString().trim(); return s.isEmpty()?null:s; }

    private List<Map<String,Object>> listarArticulos() {
        String sql="SELECT a.*, COALESCE(s.cantidad,0) AS cantidad, COALESCE(s.estado,'DISPONIBLE') AS estado_stock, COALESCE((SELECT SUM(st.cantidad) FROM stock st WHERE st.id_articulo=a.id_articulo AND st.estado='DISPONIBLE'),0) AS cantidad_disponible FROM articulo a LEFT JOIN LATERAL (SELECT SUM(cantidad) cantidad, MIN(estado) estado FROM stock WHERE id_articulo=a.id_articulo AND cantidad>0 AND estado NOT IN ('ENTREGADO','BAJA') GROUP BY id_articulo) s ON TRUE ORDER BY a.id_articulo";
        return jdbc.query(sql,(rs,n)->{Map<String,Object> m=transform(rs,"articulo"); m.put("nombre",m.get("descripcion")); m.put("cantidad",rs.getInt("cantidad")); m.put("cantidadDisponible",rs.getInt("cantidad_disponible")); m.put("estadoActual",rs.getString("estado_stock")); m.put("estadoConservacion",m.get("condicion")); return m;});
    }
    private Map<String,Object> crearArticulo(Map<String,Object> body) {
        Map<String,Object> d=toColumns(body,"articulo",false);
        d.remove("cantidad"); d.remove("estado");
        if(!d.containsKey("descripcion") && body.get("nombre")!=null) d.put("descripcion",body.get("nombre"));
        if(!d.containsKey("tipo_control")) d.put("tipo_control","CANTIDAD"); if(!d.containsKey("fecha_ingreso")) d.put("fecha_ingreso",java.time.LocalDate.now()); if(!d.containsKey("stock_minimo")) d.put("stock_minimo",0);
        if(!d.containsKey("codigo_inventario")||!d.containsKey("descripcion")||!d.containsKey("id_categoria")||!d.containsKey("id_ubicacion")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El artículo requiere código, descripción, categoría y ubicación.");
        String cols=String.join(",",d.keySet()); String marks=String.join(",",Collections.nCopies(d.size(),"?"));
        Integer id=jdbc.queryForObject("INSERT INTO articulo ("+cols+") VALUES ("+marks+") RETURNING id_articulo",Integer.class,d.values().toArray());
        int qty=body.get("cantidad") instanceof Number x?x.intValue():0; String estado=Objects.toString(body.getOrDefault("estadoActual","DISPONIBLE"));
        if(qty<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cantidad no puede ser negativa.");
        if("INDIVIDUAL".equalsIgnoreCase(Objects.toString(body.get("tipoControl"),"CANTIDAD")) && qty!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Un artículo de control individual debe tener cantidad 1.");
        if(qty>0) {
            jdbc.update("INSERT INTO stock(id_articulo,cantidad,estado) VALUES (?,?,?)",id,qty,estado);
            Integer idUsuario=number(body.get("idUsuario"));
            if(idUsuario!=null) jdbc.update("INSERT INTO movimiento(id_articulo,id_usuario,tipo_movimiento,cantidad,estado_anterior,estado_nuevo,observaciones) VALUES (?,?,?,?,?,?,?)",id,idUsuario,"AJUSTE_STOCK",qty,null,estado,"Alta manual de artículo y stock inicial");
        }
        return obtener("articulos",id);
    }
    private Map<String,Object> actualizarArticulo(int id,Map<String,Object> body) {
        Map<String,Object> d=toColumns(body,"articulo",true); Object qty=body.get("cantidad"); d.remove("cantidad"); d.remove("estado"); d.remove("id_articulo"); d.remove("estado_stock");
        if(!d.isEmpty()) {String sets=String.join(",",d.keySet().stream().map(k->k+"=?").toList());List<Object>a=new ArrayList<>(d.values());a.add(id);if(jdbc.update("UPDATE articulo SET "+sets+" WHERE id_articulo=?",a.toArray())==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Artículo no encontrado");}
        if(qty instanceof Number q) {
            int nueva=q.intValue(); if(nueva<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La cantidad no puede ser negativa.");
            Integer total=jdbc.queryForObject("SELECT COALESCE(SUM(cantidad),0) FROM stock WHERE id_articulo=? AND estado NOT IN ('ENTREGADO','BAJA')",Integer.class,id);
            Integer noDisponible=jdbc.queryForObject("SELECT COALESCE(SUM(cantidad),0) FROM stock WHERE id_articulo=? AND estado IN ('RESERVADO','PRESTADO','REPARACION')",Integer.class,id);
            if(nueva<noDisponible) throw new ResponseStatusException(HttpStatus.CONFLICT,"La cantidad no puede ser menor que las unidades reservadas, prestadas o entregadas.");
            if(nueva!=total) {
                int disponibleDeseado=nueva-noDisponible;
                jdbc.update("DELETE FROM stock WHERE id_articulo=? AND estado='DISPONIBLE'",id);
                if(disponibleDeseado>0) jdbc.update("INSERT INTO stock(id_articulo,cantidad,estado) VALUES (?,?, 'DISPONIBLE')",id,disponibleDeseado);
                Integer idUsuario=number(body.get("idUsuario"));
                if(idUsuario!=null) jdbc.update("INSERT INTO movimiento(id_articulo,id_usuario,tipo_movimiento,cantidad,estado_anterior,estado_nuevo,observaciones) VALUES (?,?,?,?,?,?,?)",id,idUsuario,"AJUSTE_STOCK",Math.max(1,Math.abs(nueva-total)),"DISPONIBLE","DISPONIBLE","Ajuste manual de stock total ("+total+" → "+nueva+")");
            }
        }
        return obtener("articulos",id);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,Object>> manejarIntegridad(DataIntegrityViolationException ex) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("error", "CONFLICTO_DE_DATOS");
        body.put("detail", "La operación entra en conflicto con datos existentes o con una relación obligatoria. Revisá el código del artículo y las selecciones antes de reintentar.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private String table(String route) { String t=TABLES.get(route.toLowerCase(Locale.ROOT)); if(t==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Recurso API desconocido: "+route); return t; }
    private Map<String,Object> toColumns(Map<String,Object> body,String table,boolean update) {
        Set<String> allowed=columns(table); Map<String,Object> out=new LinkedHashMap<>();
        for(var e:body.entrySet()) {
            String key=snake(e.getKey());
            Object value=e.getValue();
            if(value instanceof Map<?,?> nested) {
                Object id=nested.get("id"+capitalize(tableIdName(table)));
                if(id==null) id=nested.values().stream().filter(Number.class::isInstance).findFirst().orElse(null);
                if(id!=null) value=id;
            }
            String col=key;
            if(table.equals("articulo")) { if(key.equals("nombre")) col="descripcion"; if(key.equals("estado_actual")) col="estado"; if(key.equals("estado_conservacion")) col="condicion"; }
            if(table.equals("tercero")) { if(key.equals("tipo")) col="tipo_tercero"; if(key.equals("nombre_completo")) col="nombre"; if(key.equals("documento_identidad")) col="documento"; }
            if(table.equals("usuario")) { if(key.equals("password_hash")||key.equals("contrasena")) col="contrasena"; if(key.equals("username")) col="usuario"; }
            if(table.equals("donacion")) { if(key.equals("id_donante")) col="id_tercero"; if(key.equals("id_responsable_recepcion")) col="id_usuario"; if(key.equals("fecha_recepcion")) col="fecha_donacion"; }
            if(table.equals("entrega")) { if(key.equals("id_receptor")) col="id_tercero"; if(key.equals("id_responsable")) col="id_usuario"; if(key.equals("motivo_campana")) col="motivo"; }
            if(table.equals("prestamo")) { if(key.equals("id_receptor")) col="id_tercero"; if(key.equals("id_responsable")) col="id_usuario_registra"; if(key.equals("fecha_prevista_devolucion")) col="fecha_devolucion_prevista"; }
            if(table.equals("movimiento")) { if(key.equals("tipo_operacion")) col="tipo_movimiento"; if(key.equals("fecha_registro")) col="fecha_movimiento"; if(key.equals("descripcion_movimiento")) col="observaciones"; }
            // En altas, la PK se genera por la secuencia de PostgreSQL y los campos
            // nulos no deben convertirse en INSERT explícitos de NULL (causaba 409).
            if (!update && PK.containsKey(table) && PK.get(table).equals(col)) continue;
            if (!update && value == null) continue;
            if(allowed.contains(col)) out.put(col, normalizeDate(value,col));
        }
        return out;
    }
    private Set<String> columns(String table) { return new HashSet<>(jdbc.query("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position",(rs,n)->rs.getString(1),table)); }
    private Map<String,Object> transform(ResultSet rs,String table) throws SQLException {
        ResultSetMetaData md=rs.getMetaData(); Map<String,Object> m=new LinkedHashMap<>();
        for(int i=1;i<=md.getColumnCount();i++) { String col=md.getColumnLabel(i); Object val=rs.getObject(i); m.put(camel(col),val); }
        if(table.equals("tercero")) {m.put("idEntidad",m.get("idTercero"));m.put("tipo",m.get("tipoTercero"));m.put("nombreCompleto",m.get("nombre"));m.put("documentoIdentidad",m.get("documento"));}
        if(table.equals("usuario")) {m.put("passwordHash",m.get("contrasena"));m.put("rol",null);m.remove("contrasena");}
        if(table.equals("donacion")) {m.put("idDonacion",m.get("idDonacion"));m.put("fechaRecepcion",m.get("fechaDonacion"));}
        if(table.equals("movimiento")) {m.put("tipoOperacion",m.get("tipoMovimiento"));m.put("fechaRegistro",m.get("fechaMovimiento"));m.put("descripcionMovimiento",m.get("observaciones"));}
        return m;
    }
    private String snake(String s) { if(s.matches("[a-z0-9_]+")) return s; return s.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT); }
    private String camel(String s) { StringBuilder b=new StringBuilder();boolean up=false;for(char c:s.toCharArray()){if(c=='_'){up=true;}else{b.append(up?Character.toUpperCase(c):c);up=false;}}return b.toString(); }
    private Object normalizeDate(Object v,String col) { if(v instanceof String s && (col.startsWith("fecha_")||col.equals("fecha_movimiento"))) { try { if(col.endsWith("_movimiento")) return Timestamp.valueOf(s.replace('T',' ')); if(s.length()>=10)return java.sql.Date.valueOf(s.substring(0,10)); } catch(Exception ignored){} } return v; }
    private String tableIdName(String table) { return PK.getOrDefault(table,"id").replace("id_",""); }
    private String capitalize(String s) { return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1); }
    private Integer number(Object o) { return o instanceof Number n?n.intValue():null; }
}
